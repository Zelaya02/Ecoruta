const path = require('path');
const dotenv = require('dotenv');
dotenv.config({ path: path.resolve(__dirname, '../.env') });

const { getPool } = require('../src/db');
const http = require('http');

async function main() {
    console.log('====================================================');
    console.log('📋 CREANDO DENUNCIA DE PRUEBA EN BASE DE DATOS...');
    console.log('====================================================');

    const pool = getPool();
    if (!pool) {
        console.error('❌ No se pudo conectar al pool de PostgreSQL');
        process.exit(1);
    }

    const ticket = `ECO-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-P782`;
    const denunciaMock = {
        ticket: ticket,
        nombre: 'Mariana Gómez (Vecina)',
        telefono: '0981-554433',
        descripcion: 'Contenedores colapsados y acumulación de residuos comerciales/orgánicos sobre la vereda frente a plaza.',
        categoria: 'VERTEDERO_CLANDESTINO',
        zona: 'Zona Centro',
        direccion: 'Av. Mariscal López esq. Perú, Microcentro Asunción',
        latitud: -25.2908,
        longitud: -57.6254,
        fotoUrl: 'https://images.unsplash.com/photo-1530587191325-3db32d826c18?w=800'
    };

    try {
        const insertQuery = `
            INSERT INTO denuncias 
            (ticket, nombre_ciudadano, telefono, descripcion, categoria, barrio, direccion_referencia, latitud, longitud, foto_url, estado, usuario_id)
            VALUES 
            ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 'pendiente', 1)
            ON CONFLICT (ticket) DO UPDATE 
            SET descripcion = EXCLUDED.descripcion, estado = 'pendiente'
            RETURNING id, ticket, barrio, direccion_referencia, descripcion, latitud, longitud, foto_url, estado;
        `;

        const res = await pool.query(insertQuery, [
            denunciaMock.ticket,
            denunciaMock.nombre,
            denunciaMock.telefono,
            denunciaMock.descripcion,
            denunciaMock.categoria,
            denunciaMock.zona,
            denunciaMock.direccion,
            denunciaMock.latitud,
            denunciaMock.longitud,
            denunciaMock.fotoUrl
        ]);

        const denunciaDb = res.rows[0];
        console.log(`✅ Denuncia registrada con éxito en PostgreSQL:`);
        console.log(`   - ID Denuncia: #${denunciaDb.id}`);
        console.log(`   - Ticket:      ${denunciaDb.ticket}`);
        console.log(`   - Zona:        ${denunciaDb.barrio}`);
        console.log(`   - Ubicación:   ${denunciaDb.direccion_referencia}`);
        console.log(`   - Coordenadas: ${denunciaDb.latitud}, ${denunciaDb.longitud}`);
        console.log(`   - Estado:      ${denunciaDb.estado}`);
        console.log('----------------------------------------------------');

        console.log('📡 DISPARANDO DESPACHO AL BOT DE TELEGRAM...');
        console.log(`   Destinatario esperado: Telegram ID ${process.env.TEST_DRIVER_TELEGRAM_ID || '8727434553'} (${denunciaDb.barrio})`);

        const payload = JSON.stringify({
            denunciaId: denunciaDb.id,
            ticket: denunciaDb.ticket,
            zona: denunciaDb.barrio,
            direccion: denunciaDb.direccion_referencia,
            descripcion: denunciaDb.descripcion,
            latitud: denunciaDb.latitud,
            longitud: denunciaDb.longitud,
            fotoUrl: denunciaDb.foto_url
        });

        const port = process.env.PORT || 4000;
        const req = http.request({
            hostname: 'localhost',
            port: port,
            path: '/api/despacho/denuncia',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload)
            }
        }, (resp) => {
            let data = '';
            resp.on('data', chunk => data += chunk);
            resp.on('end', () => {
                console.log(`\n📬 [Respuesta del Servicio de Despacho HTTP ${resp.statusCode}]:`);
                try {
                    const jsonResp = JSON.parse(data);
                    console.log(JSON.stringify(jsonResp, null, 2));
                } catch (e) {
                    console.log(data);
                }
                console.log('====================================================');
                pool.end();
            });
        });

        req.on('error', (e) => {
            console.error('❌ Error conectando al microservicio HTTP:', e.message);
            pool.end();
        });

        req.write(payload);
        req.end();

    } catch (err) {
        console.error('❌ Error en ejecución:', err.message);
        pool.end();
    }
}

main();
