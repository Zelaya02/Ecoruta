package com.ruteo.repository;

import com.ruteo.model.Usuario;
import org.mindrot.jbcrypt.BCrypt;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UsuarioRepository {
    private final String dbUrl;
    private final String dbUser;
    private final String dbPass;

    public UsuarioRepository(String dbUrl, String dbUser, String dbPass) {
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPass = dbPass;
    }

    public Usuario login(String username, String password) {
        String sql = "SELECT * FROM usuarios WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String storedPassword = rs.getString("password");
                    boolean matches = false;

                    if (storedPassword != null && storedPassword.startsWith("$2a$")) {
                        matches = BCrypt.checkpw(password, storedPassword);
                    } else if (storedPassword != null) {
                        matches = password.equals(storedPassword);
                        if (matches) {
                            String hashed = BCrypt.hashpw(password, BCrypt.gensalt());
                            actualizarPassword(username, hashed);
                        }
                    }

                    if (matches) {
                        Usuario user = new Usuario();
                        user.setId(rs.getInt("id"));
                        user.setUsername(rs.getString("username"));
                        user.setNombre(rs.getString("nombre"));
                        user.setRol(rs.getString("rol") != null ? rs.getString("rol") : "gestor");
                        try {
                            user.setActivo(rs.getBoolean("activo"));
                        } catch (SQLException ignored) {
                            user.setActivo(true);
                        }
                        return user;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error en login: " + e.getMessage());
        }
        return null;
    }

    public List<Usuario> listarUsuarios() {
        List<Usuario> lista = new ArrayList<>();
        String sql = "SELECT id, username, nombre, rol, COALESCE(activo, true) as activo FROM usuarios ORDER BY id ASC";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Usuario u = new Usuario();
                u.setId(rs.getInt("id"));
                u.setUsername(rs.getString("username"));
                u.setNombre(rs.getString("nombre"));
                u.setRol(rs.getString("rol") != null ? rs.getString("rol") : "gestor");
                u.setActivo(rs.getBoolean("activo"));
                lista.add(u);
            }
        } catch (SQLException e) {
            System.err.println("Error listarUsuarios: " + e.getMessage());
        }
        return lista;
    }

    public boolean crearUsuario(String username, String nombre, String rol, String password) {
        String hashed = BCrypt.hashpw(password, BCrypt.gensalt());
        String sql = "INSERT INTO usuarios (username, nombre, rol, password, activo) VALUES (?, ?, ?, ?, true)";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, nombre);
            pstmt.setString(3, rol);
            pstmt.setString(4, hashed);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("Error crearUsuario: " + e.getMessage());
            return false;
        }
    }

    public boolean actualizarUsuario(int id, String nombre, String rol, boolean activo) {
        String sql = "UPDATE usuarios SET nombre = ?, rol = ?, activo = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, nombre);
            pstmt.setString(2, rol);
            pstmt.setBoolean(3, activo);
            pstmt.setInt(4, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error actualizarUsuario: " + e.getMessage());
            return false;
        }
    }

    public boolean resetPassword(int id, String nuevaPassword) {
        String hashed = BCrypt.hashpw(nuevaPassword, BCrypt.gensalt());
        String sql = "UPDATE usuarios SET password = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, hashed);
            pstmt.setInt(2, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error resetPassword: " + e.getMessage());
            return false;
        }
    }

    private void actualizarPassword(String username, String newHash) {
        String sql = "UPDATE usuarios SET password = ? WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newHash);
            pstmt.setString(2, username);
            pstmt.executeUpdate();
            System.out.println("✅ Password migrado a BCrypt para el usuario: " + username);
        } catch (SQLException e) {
            System.err.println("Error al migrar password: " + e.getMessage());
        }
    }
}