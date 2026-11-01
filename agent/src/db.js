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
                console.error('⚠️ [Agent PostgreSQL Error]:', err.message);
            });
        } catch (err) {
            console.warn('⚠️ No se pudo inicializar pool en Agent:', err.message);
        }
    }
    return pool;
}

module.exports = { getPool };
