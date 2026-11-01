const dns = require('dns');
dns.setDefaultResultOrder('ipv4first');

const config = require('./config');
const { initBot } = require('./bot');
const { createServer } = require('./server');
const { getPool } = require('./db');

async function start() {
    console.log('====================================================');
    console.log('🚛 NEXO EcoRuta - Servicio de Bot de Telegram');
    console.log('====================================================');
    console.log(`📍 Zona por Defecto:        ${config.defaultZone}`);
    console.log(`🆔 Conductor de Pruebas:    Telegram ID ${config.testDriverTelegramId}`);
    console.log(`🔌 Backend Java URL:        ${config.backendApiUrl}`);
    console.log(`🗄️  PostgreSQL:              ${config.databaseUrl ? 'Configurado' : 'No configurado'}`);

    // Probar conexión inicial a PostgreSQL
    const pool = getPool();
    if (pool) {
        try {
            const client = await pool.connect();
            console.log('✅ Conexión con PostgreSQL establecida con éxito.');
            client.release();
        } catch (dbErr) {
            console.warn('⚠️ No se pudo conectar a PostgreSQL (se reintentará en demanda):', dbErr.message);
        }
    }

    // Inicializar Bot de Telegram
    const bot = initBot();
    if (bot) {
        bot.launch()
            .then(() => {
                console.log('🤖 Bot de Telegram en línea y escuchando comandos (Long Polling)...');
            })
            .catch((err) => {
                console.error('❌ Error al iniciar Telegraf bot.launch():', err.message);
            });
    }

    // Iniciar Servidor HTTP de Despacho
    const app = createServer();
    const server = app.listen(config.port, () => {
        console.log(`🚀 Servidor HTTP de despacho listo en: http://localhost:${config.port}`);
        console.log(`📥 Endpoint de despacho: POST http://localhost:${config.port}/api/despacho/denuncia`);
        console.log('====================================================');
    });

    // Cierre limpio
    const shutdown = () => {
        console.log('\n🛑 Deteniendo servicio de Telegram...');
        if (bot) bot.stop('SIGINT');
        server.close(() => {
            console.log('👋 Servidor HTTP detenido.');
            process.exit(0);
        });
    };

    process.once('SIGINT', shutdown);
    process.once('SIGTERM', shutdown);
}

start().catch((err) => {
    console.error('💥 Error crítico al iniciar microservicio:', err);
    process.exit(1);
});
