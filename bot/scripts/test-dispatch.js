const http = require('http');
const path = require('path');
const dotenv = require('dotenv');

dotenv.config({ path: path.resolve(__dirname, '../.env') });

const config = require('../src/config');
const { despacharDenunciaAConductor, initBot } = require('../src/bot');

const MOCK_DENUNCIA = {
    denunciaId: 991,
    ticket: `ECO-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-T991`,
    zona: config.defaultZone || 'Zona Centro',
    direccion: 'Calle Palma e/ Independencia Nacional, Microcentro Asunción',
    descripcion: 'Contenedor desbordado y acumulación de cajas de cartón y residuos comerciales en vía pública.',
    latitud: -25.2825,
    longitud: -57.6351,
    fotoUrl: 'https://images.unsplash.com/photo-1530587191325-3db32d826c18?w=800'
};

async function checkServiceRunning(port) {
    return new Promise((resolve) => {
        const req = http.get(`http://localhost:${port}/health`, (res) => {
            resolve(res.statusCode === 200);
        });
        req.on('error', () => resolve(false));
        req.setTimeout(1000, () => {
            req.destroy();
            resolve(false);
        });
    });
}

async function sendDispatchHttp(port, payload) {
    return new Promise((resolve, reject) => {
        const postData = JSON.stringify(payload);
        const options = {
            hostname: 'localhost',
            port: port,
            path: '/api/despacho/denuncia',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(postData)
            }
        };

        const req = http.request(options, (res) => {
            let body = '';
            res.on('data', (chunk) => body += chunk);
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(body);
                    resolve({ statusCode: res.statusCode, body: parsed });
                } catch (e) {
                    resolve({ statusCode: res.statusCode, body });
                }
            });
        });

        req.on('error', (e) => reject(e));
        req.write(postData);
        req.end();
    });
}

async function run() {
    console.log('====================================================');
    console.log('🚛 SIMULACIÓN DE DESPACHO INTERACTIVO A CONDUCTOR');
    console.log('====================================================');
    console.log(`👤 Conductor Objetivo: Telegram ID ${config.testDriverTelegramId}`);
    console.log(`📍 Zona de Cobertura:  ${MOCK_DENUNCIA.zona}`);
    console.log(`🎫 Ticket Generado:     ${MOCK_DENUNCIA.ticket}`);
    console.log(`📌 Dirección:          ${MOCK_DENUNCIA.direccion}`);
    console.log(`📝 Descripción:        ${MOCK_DENUNCIA.descripcion}`);
    console.log(`🗺️  Coordenadas:        ${MOCK_DENUNCIA.latitud}, ${MOCK_DENUNCIA.longitud}`);
    console.log('----------------------------------------------------');

    const port = config.port || 4000;
    const isRunning = await checkServiceRunning(port);

    if (isRunning) {
        console.log(`📡 Microservicio detectado en http://localhost:${port}. Enviando vía HTTP POST...`);
        try {
            const res = await sendDispatchHttp(port, MOCK_DENUNCIA);
            console.log(`\n✅ [Respuesta HTTP ${res.statusCode}]:`);
            console.log(JSON.stringify(res.body, null, 2));
        } catch (err) {
            console.error('❌ Error en llamada HTTP:', err.message);
        }
    } else {
        console.log(`ℹ️  Microservicio no está corriendo en puerto ${port}. Ejecutando despacho autónomo directo...`);
        initBot();
        try {
            const res = await despacharDenunciaAConductor(MOCK_DENUNCIA);
            console.log('\n✅ [Resultado de Despacho Directo]:');
            console.log(JSON.stringify(res, null, 2));
        } catch (err) {
            console.error('❌ Error en despacho directo:', err.message);
        }
    }

    console.log('----------------------------------------------------');
    console.log('📲 FLUJO EN TELEGRAM PARA EL CONDUCTOR:');
    console.log('1. El conductor (ID: ' + config.testDriverTelegramId + ') recibe la alerta interactiva.');
    console.log('2. Presiona: [✅ Aceptar Recolección]');
    console.log('   ↳ El mensaje cambia a: "🚚 EN CAMINO A RECOLECCIÓN"');
    console.log('   ↳ Aparece el botón: [🏁 Marcar como Recolectado] y mapa GPS.');
    console.log('3. Al llegar y limpiar la zona, presiona: [🏁 Marcar como Recolectado]');
    console.log('   ↳ La denuncia se actualiza a estado "cerrada" en PostgreSQL.');
    console.log('   ↳ El mensaje se actualiza a: "🏁 RECOLECCIÓN FINALIZADA CON ÉXITO ✅".');
    console.log('====================================================\n');
}

run();
