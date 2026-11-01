const path = require('path');
const dotenv = require('dotenv');

// Cargar variables de entorno desde .env local de la carpeta /bot
dotenv.config({ path: path.resolve(__dirname, '../.env') });

const config = {
    telegramToken: process.env.TELEGRAM_BOT_TOKEN || '',
    testDriverTelegramId: String(process.env.TEST_DRIVER_TELEGRAM_ID || '8727434553').trim(),
    defaultZone: process.env.DEFAULT_ZONE || 'Zona Centro',
    port: parseInt(process.env.PORT || '4000', 10),
    backendApiUrl: process.env.BACKEND_API_URL || 'http://localhost:8085',
    databaseUrl: process.env.DATABASE_URL || 'postgresql://postgres:Zelaya11@localhost:5432/ruteo_db'
};

module.exports = config;
