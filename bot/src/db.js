const { Pool } = require('pg');
const config = require('./config');

let pool = null;

function getPool() {
    if (!pool && config.databaseUrl) {
        try {
            pool = new Pool({
                connectionString: config.databaseUrl,
                ssl: config.databaseUrl.includes('sslmode=require') || config.databaseUrl.includes('aivencloud.com')
                    ? { rejectUnauthorized: false }
                    : false,
                connectionTimeoutMillis: 5000
            });

            pool.on('error', (err) => {
                console.error('⚠️ [PostgreSQL Pool Error]:', err.message);
            });
        } catch (err) {
            console.warn('⚠️ No se pudo inicializar el pool de PostgreSQL:', err.message);
        }
    }
    return pool;
}

/**
 * Obtiene los detalles de una denuncia por su ID
 */
async function obtenerDenunciaPorId(id) {
    const p = getPool();
    if (!p) return null;
    try {
        const res = await p.query('SELECT * FROM denuncias WHERE id = $1', [id]);
        return res.rows[0] || null;
    } catch (err) {
        console.warn(`⚠️ Error al consultar denuncia #${id} en BD:`, err.message);
        return null;
    }
}

/**
 * Actualiza el estado de una denuncia en la base de datos (PostgreSQL)
 * Estados soportados por el backend: pendiente -> en_proceso -> cerrada | rechazada
 */
async function actualizarEstadoDenuncia(id, estado, observacion = '') {
    const p = getPool();
    if (!p) return { success: false, reason: 'Sin conexión a base de datos' };

    try {
        let query;
        let params;

        if (estado === 'cerrada' || estado === 'RESUELTO' || estado === 'FINALIZADO') {
            const estadoNormalizado = 'cerrada';
            query = `
                UPDATE denuncias 
                SET estado = $1, observacion_cierre = $2, fecha_cierre = CURRENT_TIMESTAMP 
                WHERE id = $3 
                RETURNING id, ticket, estado, observacion_cierre, fecha_cierre
            `;
            params = [estadoNormalizado, observacion, id];
        } else {
            query = `
                UPDATE denuncias 
                SET estado = $1, observacion_cierre = $2 
                WHERE id = $3 
                RETURNING id, ticket, estado, observacion_cierre
            `;
            params = [estado, observacion, id];
        }

        const res = await p.query(query, params);
        if (res.rowCount > 0) {
            return { success: true, denuncia: res.rows[0] };
        }
        return { success: false, reason: `No se encontró la denuncia con ID ${id}` };
    } catch (err) {
        console.error(`❌ Error actualizando denuncia #${id} a estado '${estado}':`, err.message);
        return { success: false, error: err.message };
    }
}

/**
 * Actualiza el estado de una ruta y sus denuncias asociadas
 */
async function actualizarEstadoRuta(token, estado, motivo = '') {
    const p = getPool();
    if (!p) return { success: false, reason: 'Sin conexión a base de datos' };

    try {
        await p.query('UPDATE rutas_generadas SET estado = $1 WHERE token = $2', [estado, token]);

        if (estado === 'rechazada_chofer' || estado === 'rechazada') {
            // Liberar las denuncias de esta ruta para reasignación
            await p.query(
                `UPDATE denuncias 
                 SET estado = 'pendiente', 
                     ruta_token = NULL, 
                     observacion_cierre = $1 
                 WHERE ruta_token = $2`,
                [`Reasignación requerida: Chofer rechazó la ruta (${motivo})`, token]
            );
        } else if (estado === 'en_curso' || estado === 'aceptada_chofer') {
            await p.query(
                `UPDATE denuncias 
                 SET estado = 'en_proceso', 
                     observacion_cierre = $1 
                 WHERE ruta_token = $2`,
                [`Ruta en ejecución activa por el chofer asignado (${motivo})`, token]
            );
        } else if (estado === 'finalizada' || estado === 'completada') {
            await p.query(
                `UPDATE denuncias 
                 SET estado = 'cerrada', 
                     fecha_cierre = CURRENT_TIMESTAMP, 
                     observacion_cierre = $1 
                 WHERE ruta_token = $2`,
                [`Recolección completada por el chofer (${motivo})`, token]
            );
        }

        console.log(`✅ [DB Ruta]: Token ${token} actualizado a estado '${estado}'`);
        return { success: true, token, estado };
    } catch (err) {
        console.error(`❌ Error actualizando ruta ${token}:`, err.message);
        return { success: false, error: err.message };
    }
}

module.exports = {
    getPool,
    obtenerDenunciaPorId,
    actualizarEstadoDenuncia,
    actualizarEstadoRuta
};
