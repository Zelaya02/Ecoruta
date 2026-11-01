import 'dart:convert';
import 'package:http/http.dart' as http;
import 'app_config.dart';

class AuthService {
  static final AuthService _instance = AuthService._internal();
  factory AuthService() => _instance;
  AuthService._internal();

  String? token;
  String? username;
  String? nombre;
  String? rol;

  bool get isLoggedIn => token != null && token!.isNotEmpty;

  Future<Map<String, dynamic>> login(String user, String pass) async {
    try {
      final response = await http
          .post(
            Uri.parse(AppConfig.loginUrl),
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({
              'username': user.trim(),
              'password': pass.trim(),
            }),
          )
          .timeout(const Duration(seconds: 10));

      final data = jsonDecode(response.body);

      if (response.statusCode == 200 && data['success'] == true) {
        token = data['token'];
        username = user.trim();
        nombre = data['usuario'] ?? user.trim();
        rol = data['rol'] ?? 'ciudadano';
        return {'success': true, 'message': 'Bienvenido, $nombre'};
      } else {
        return {
          'success': false,
          'message': data['message'] ?? 'Credenciales inválidas',
        };
      }
    } catch (e) {
      return {
        'success': false,
        'message': 'Error de conexión con EcoRuta ($e)',
      };
    }
  }

  Future<Map<String, dynamic>> registro({
    required String user,
    required String pass,
    required String nombreCompleto,
    String? telefono,
  }) async {
    try {
      final response = await http
          .post(
            Uri.parse(AppConfig.registroUrl),
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({
              'username': user.trim(),
              'password': pass.trim(),
              'nombre': nombreCompleto.trim(),
              'telefono': telefono?.trim() ?? '',
            }),
          )
          .timeout(const Duration(seconds: 10));

      final data = jsonDecode(response.body);

      if ((response.statusCode == 200 || response.statusCode == 201) &&
          data['success'] == true) {
        token = data['token'];
        username = user.trim();
        nombre = nombreCompleto.trim();
        rol = 'ciudadano';
        return {'success': true, 'message': 'Cuenta creada exitosamente'};
      } else {
        return {
          'success': false,
          'message': data['message'] ?? 'Error al registrar el usuario',
        };
      }
    } catch (e) {
      return {
        'success': false,
        'message': 'Error de conexión al registrar ($e)',
      };
    }
  }

  void logout() {
    token = null;
    username = null;
    nombre = null;
    rol = null;
  }
}
