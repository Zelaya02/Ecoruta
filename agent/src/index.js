const dns = require('dns');
dns.setDefaultResultOrder('ipv4first');

const config = require('./config');
const { createAgentServer } = require('./server');
const { getPool } = require('./db');

async function main() {
    console.log('====================================================');
    console.log('🛡️  ECORUTA - AGENTE DE VERIFICACIÓN Y ANTI-SPAM');
    console.log('====================================================');
    console.log(`📡 Ecoruta Backend:     ${config.ecorutaBackendUrl}`);
    console.log(`🔌 Puerto Agente:       ${config.port}`);
    console.log(`🎯 Umbral Confianza:    ${config.minConfidence * 100}%`);
    console.log(`📍 Radio Duplicados:    ${config.duplicateRadiusMeters} metros`);
    console.log(`⏰ Ventana Duplicados:  ${config.duplicateWindowHours} horas`);

    const pool = getPool();
    if (pool) {
        try {
            const client = await pool.connect();
            console.log('✅ Conexión con PostgreSQL verificada.');
            client.release();
        } catch (e) {
            console.warn('⚠️ Base de datos no disponible para duplicados:', e.message);
        }
    }

    const app = createAgentServer();
    const server = app.listen(config.port, '0.0.0.0', () => {
        console.log(`🚀 Servicio Agente activo en: http://0.0.0.0:${config.port}`);
        console.log(`📥 Endpoint de validación: POST http://0.0.0.0:${config.port}/api/denuncias/validar-y-enviar`);
        console.log('====================================================');
    });

    const shutdown = () => {
        console.log('\n🛑 Deteniendo servicio del Agente...');
        server.close(() => {
            console.log('👋 Servicio detenido.');
            process.exit(0);
        });
    };

    process.once('SIGINT', shutdown);
    process.once('SIGTERM', shutdown);
}

main().catch(err => {
    console.error('💥 Error crítico en Agente Ecoruta:', err);
    process.exit(1);
});
