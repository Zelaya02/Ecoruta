const http = require('http');
const https = require('https');
const config = require('./config');

/**
 * Cliente HTTP para comunicar el agente con el backend de Ecoruta
 */

function httpRequest(urlStr, options = {}, postData = null) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(urlStr);
        const isHttps = parsedUrl.protocol === 'https:';
        const client = isHttps ? https : http;

        const reqOptions = {
            hostname: parsedUrl.hostname,
            port: parsedUrl.port || (isHttps ? 443 : 80),
            path: parsedUrl.pathname + parsedUrl.search,
            method: options.method || 'GET',
            headers: options.headers || {}
        };

        const req = client.request(reqOptions, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                let parsed = null;
                try {
                    parsed = JSON.parse(body);
                } catch (e) {
                    parsed = body;
                }
                resolve({
                    status: res.statusCode,
                    headers: res.headers,
                    data: parsed
                });
            });
        });

        req.on('error', err => reject(err));
        req.setTimeout(8000, () => {
            req.destroy();
            reject(new Error('Timeout de conexión con Ecoruta Backend'));
        });

        if (postData) {
            req.write(typeof postData === 'string' ? postData : JSON.stringify(postData));
        }
        req.end();
    });
}

/**
 * Mapea el payload saliente de la creación de reportes para coincidir exactamente
 * con el contrato JSON esperado por Ecoruta (POST /api/denuncias)
 */
async function enviarDenunciaAEcoruta(datosReporte) {
    const url = `${config.ecorutaBackendUrl}/api/denuncias`;

    // Mapeo riguroso según el contrato requerido
    const payloadContrato = {
        ticket: datosReporte.ticket || undefined,
        descripcion: datosReporte.descripcion || '',
        categoria: datosReporte.categoria || 'VERTEDERO_CLANDESTINO',
        barrio: datosReporte.barrio || 'Zona Centro',
        direccion_referencia: datosReporte.direccion_referencia || datosReporte.direccion || '',
        latitud: parseFloat(datosReporte.latitud),
        longitud: parseFloat(datosReporte.longitud),
        foto_url: datosReporte.foto_url || datosReporte.fotoUrl || '',
        nombre_ciudadano: datosReporte.nombre_ciudadano || datosReporte.nombre || '',
        telefono: datosReporte.telefono || '',
        estado: datosReporte.estado || 'pendiente',
        observacion_cierre: datosReporte.observacion_cierre || datosReporte.observaciones || ''
    };

    const postBody = JSON.stringify(payloadContrato);

    const res = await httpRequest(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(postBody)
        }
    }, postBody);

    if (res.status >= 200 && res.status < 300) {
        return {
            success: true,
            status: res.status,
            denuncia: res.data
        };
    } else {
        throw new Error(`Ecoruta Backend retornó HTTP ${res.status}: ${JSON.stringify(res.data)}`);
    }
}

/**
 * Consulta pública del ticket para rastreo del ciudadano
 * GET /api/denuncias?ticket=ECO-...
 */
async function consultarTicketEcoruta(ticket) {
    if (!ticket) throw new Error('Ticket requerido');
    const cleanTicket = ticket.trim().toUpperCase();
    const url = `${config.ecorutaBackendUrl}/api/denuncias?ticket=${encodeURIComponent(cleanTicket)}`;

    const res = await httpRequest(url, { method: 'GET' });

    if (res.status === 200) {
        return {
            success: true,
            ticket: cleanTicket,
            denuncia: res.data
        };
    } else if (res.status === 404) {
        return {
            success: false,
            notFound: true,
            mensaje: `El ticket ${cleanTicket} no existe en el sistema Ecoruta.`
        };
    } else {
        throw new Error(`Error consultando ticket (${res.status}): ${JSON.stringify(res.data)}`);
    }
}

module.exports = {
    httpRequest,
    enviarDenunciaAEcoruta,
    consultarTicketEcoruta
};
