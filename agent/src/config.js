const path = require('path');
const dotenv = require('dotenv');

dotenv.config({ path: path.resolve(__dirname, '../.env') });

const config = {
    ecorutaBackendUrl: (process.env.ECORUTA_BACKEND_URL || 'http://localhost:8085').replace(/\/$/, ''),
    port: parseInt(process.env.PORT || '5050', 10),
    databaseUrl: process.env.DATABASE_URL || 'postgresql://postgres:Zelaya11@localhost:5432/ruteo_db',
    minConfidence: parseFloat(process.env.AGENT_MIN_CONFIDENCE || '0.70'),
    duplicateRadiusMeters: parseInt(process.env.AGENT_DUPLICATE_RADIUS_METERS || '150', 10),
    duplicateWindowHours: parseInt(process.env.AGENT_DUPLICATE_WINDOW_HOURS || '48', 10),
    geoBounds: {
        latMin: parseFloat(process.env.AGENT_GEO_BOUNDS_LAT_MIN || '-25.40'),
        latMax: parseFloat(process.env.AGENT_GEO_BOUNDS_LAT_MAX || '-25.18'),
        lonMin: parseFloat(process.env.AGENT_GEO_BOUNDS_LON_MIN || '-57.72'),
        lonMax: parseFloat(process.env.AGENT_GEO_BOUNDS_LON_MAX || '-57.48')
    },
    geminiApiKey: process.env.GEMINI_API_KEY || ''
};

module.exports = config;
