const express = require('express');
const config = require('./config');
const { despacharDenunciaAConductor, despacharRutaAConductor } = require('./bot');
const { getAllDrivers, registerOrUpdateDriver } = require('./drivers');
const { obtenerDenunciaPorId } = require('./db');

function createServer() {
    const app = express();

    app.use(express.json());

    // CORS básico para permitir integraciones locales
    app.use((req, res, next) => {
        res.header('Access-Control-Allow-Origin', '*');
        res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
        res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept, Authorization');
        if (req.method === 'OPTIONS') {
            return res.sendStatus(200);
        }
        next();
    });

    // ----------------------------------------------------
    // GET /health
    // ----------------------------------------------------
    app.get('/health', (req, res) => {
        res.json({
            status: 'UP',
            service: 'NEXO Telegram Bot Dispatcher',
            timestamp: new Date().toISOString(),
            testDriverTelegramId: config.testDriverTelegramId,
            defaultZone: config.defaultZone
        });
    });

    // ----------------------------------------------------
    // GET /api/conductores
    // ----------------------------------------------------
    app.get('/api/conductores', (req, res) => {
        res.json({
            conductores: getAllDrivers()
        });
    });

    // ----------------------------------------------------
    // POST /api/conductores/registrar
    // ----------------------------------------------------
    app.post('/api/conductores/registrar', (req, res) => {
        const { telegramId, nombre, zona, vehiculo } = req.body;
        if (!telegramId) {
            return res.status(400).json({ error: 'telegramId es obligatorio' });
        }

        const conductor = registerOrUpdateDriver(telegramId, { nombre, zona, vehiculo });
        res.json({
            message: 'Conductor registrado con éxito',
            conductor
        });
    });

    // ----------------------------------------------------
    // POST /api/despacho/denuncia
    // Despacha una denuncia ciudadana al conductor de la zona
    // ----------------------------------------------------
    app.post('/api/despacho/denuncia', async (req, res) => {
        try {
            const {
                denunciaId,
                ticket,
                zona = config.defaultZone,
                direccion,
                descripcion,
                latitud,
                longitud,
                fotoUrl
            } = req.body;

            if (!denunciaId) {
                return res.status(400).json({
                    error: 'denunciaId es requerido para el despacho.'
                });
            }

            // Si faltan detalles en el payload, intentar enriquecerlos desde PostgreSQL
            let denunciaData = { ...req.body };
            const denunciaDb = await obtenerDenunciaPorId(denunciaId);
            if (denunciaDb) {
                denunciaData = {
                    denunciaId,
                    ticket: ticket || denunciaDb.ticket,
                    zona: zona || denunciaDb.barrio || config.defaultZone,
                    direccion: direccion || denunciaDb.direccion_referencia || denunciaDb.barrio,
                    descripcion: descripcion || denunciaDb.descripcion,
                    latitud: latitud || denunciaDb.latitud,
                    longitud: longitud || denunciaDb.longitud,
                    fotoUrl: fotoUrl || denunciaDb.foto_url
                };
            }

            console.log(`📡 [Despacho HTTP]: Procesando denuncia #${denunciaId} para zona '${denunciaData.zona}'`);

            const resultado = await despacharDenunciaAConductor(denunciaData);

            res.status(200).json({
                success: true,
                mensaje: 'Denuncia despachada al conductor asignado',
                despacho: resultado
            });
        } catch (error) {
            console.error('❌ Error en /api/despacho/denuncia:', error.message);
            res.status(500).json({
                success: false,
                error: error.message
            });
        }
    });

    // ----------------------------------------------------
    // POST /api/despacho/ruta
    // Despacha una hoja de ruta completa generada al Telegram del chofer
    // ----------------------------------------------------
    app.post('/api/despacho/ruta', async (req, res) => {
        try {
            console.log('📡 [Despacho Ruta HTTP]: Recibida solicitud de despacho por Telegram:', req.body.token);
            const resultado = await despacharRutaAConductor(req.body);
            res.status(200).json(resultado);
        } catch (error) {
            console.error('❌ Error en /api/despacho/ruta:', error.message);
            res.status(500).json({
                success: false,
                error: error.message
            });
        }
    });

    return app;
}

module.exports = { createServer };
