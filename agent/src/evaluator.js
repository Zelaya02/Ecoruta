const config = require('./config');
const { CATEGORIAS_VALIDAS, TERMINOS_GENERALES_RESIDUOS, PATRONES_SPAM, BARRIOS_JURISDICCION } = require('./rules');
const { validarDenunciaConGemini } = require('./geminiVision');

/**
 * Fórmula de Haversine para calcular distancia entre dos coordenadas en metros
 */
function calcularDistanciaMetros(lat1, lon1, lat2, lon2) {
    const R = 6371e3; // Radio de la Tierra en metros
    const phi1 = (lat1 * Math.PI) / 180;
    const phi2 = (lat2 * Math.PI) / 180;
    const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
    const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

    const a =
        Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
        Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return R * c;
}

/**
 * Evalúa la coherencia del texto y la foto contra la temática de residuos y detecta spam
 */
async function evaluarCoherenciaTextoFoto(datos) {
    const { descripcion = '', categoria = 'VERTEDERO_CLANDESTINO', foto_url = '' } = datos;
    const texto = descripcion.trim().toLowerCase();
    const problemas = [];
    let puntos = 1.0;

    // 1. Longitud básica
    if (texto.length < 8) {
        problemas.push('La descripción es demasiado escueta o carece de información útil');
        puntos -= 0.35;
    }

    // 2. Patrones de SPAM / Publicidad / Teclado aleatorio
    for (const patron of PATRONES_SPAM) {
        if (patron.test(texto)) {
            problemas.push('Se detectaron patrones compatibles con spam, publicidad o caracteres aleatorios');
            puntos -= 0.70;
            break;
        }
    }

    // 3. Relevancia semántica con el dominio de residuos y limpieza urbana
    const infoCat = CATEGORIAS_VALIDAS[categoria] || CATEGORIAS_VALIDAS.VERTEDERO_CLANDESTINO;
    const palabrasEsperadas = [...infoCat.palabrasClave, ...TERMINOS_GENERALES_RESIDUOS];

    let coincidencias = 0;
    for (const palabra of palabrasEsperadas) {
        if (texto.includes(palabra)) {
            coincidencias++;
        }
    }

    if (coincidencias === 0 && texto.length > 0) {
        problemas.push('El texto no menciona términos reconocidos de recolección, residuos, contenedores o poda');
        puntos -= 0.30;
    } else if (coincidencias >= 2) {
        puntos = Math.min(1.0, puntos + 0.15); // Bonificación por alta coherencia
    }

    // 4. Verificación de Foto (si fue suministrada)
    let fotoValida = false;
    let fotoScore = 0.5; // Neutral si no hay foto

    if (foto_url && foto_url.trim().length > 0) {
        const urlStr = foto_url.trim().toLowerCase();
        const esUrlValida = /^https?:\/\/.+/i.test(urlStr);

        if (!esUrlValida) {
            problemas.push('El formato del enlace de la fotografía no es una URL válida');
            fotoScore = 0.1;
        } else {
            // Verificar extensiones de imagen comunes o hosts confiables
            const extensiones = ['.jpg', '.jpeg', '.png', '.webp', 'images.unsplash.com', 'cloudinary', 'imgur'];
            const tieneExtension = extensiones.some(ext => urlStr.includes(ext));

            if (tieneExtension) {
                fotoValida = true;
                fotoScore = 0.95;
            } else {
                fotoValida = true;
                fotoScore = 0.70;
            }
        }
    } else {
        fotoScore = 0.60; // Foto opcional pero con puntaje neutro
    }

    const puntajeFinal = Math.max(0.0, Math.min(1.0, (puntos * 0.7) + (fotoScore * 0.3)));

    return {
        valido: puntajeFinal >= 0.55 && !problemas.some(p => p.includes('spam')),
        puntaje: parseFloat(puntajeFinal.toFixed(2)),
        problemas,
        coincidenciasSemanticas: coincidencias,
        fotoValida,
        categoriaReconocida: infoCat.codigo
    };
}

/**
 * Geovalidación: Valida coordenadas dentro de la jurisdicción de Asunción y Gran Asunción
 */
function evaluarGeovalidacion(datos) {
    const lat = parseFloat(datos.latitud);
    const lon = parseFloat(datos.longitud);
    const barrio = (datos.barrio || '').trim();
    const problemas = [];

    if (isNaN(lat) || isNaN(lon)) {
        return {
            valido: false,
            puntaje: 0.0,
            problemas: ['Coordenadas geográficas inválidas o no numéricas']
        };
    }

    // Comprobar si las coordenadas están en el rango de Gran Asunción
    const { latMin, latMax, lonMin, lonMax } = config.geoBounds;
    const dentroDeLimites = lat >= latMin && lat <= latMax && lon >= lonMin && lon <= lonMax;

    if (!dentroDeLimites) {
        problemas.push(`Coordenadas (${lat}, ${lon}) fuera del radio de jurisdicción municipal permitido`);
        return {
            valido: false,
            puntaje: 0.1,
            dentroDeLimites: false,
            problemas
        };
    }

    // Calcular distancia al centro de Asunción (-25.2867, -57.6333)
    const distCentroKm = calcularDistanciaMetros(lat, lon, -25.2867, -57.6333) / 1000;

    // Validación de barrio contra lista conocida
    let barrioReconocido = false;
    if (barrio) {
        barrioReconocido = BARRIOS_JURISDICCION.some(b => b.toLowerCase() === barrio.toLowerCase());
    }

    const puntaje = barrioReconocido ? 1.0 : 0.85;

    return {
        valido: true,
        puntaje,
        dentroDeLimites: true,
        distanciaCentroKm: parseFloat(distCentroKm.toFixed(2)),
        barrioReconocido,
        problemas
    };
}

/**
 * Detección de duplicados y spam por proximidad geográfica y temporal
 */
async function evaluarDuplicadosYSpam(datos, pool) {
    const lat = parseFloat(datos.latitud);
    const lon = parseFloat(datos.longitud);
    const categoria = datos.categoria || 'VERTEDERO_CLANDESTINO';

    if (!pool || isNaN(lat) || isNaN(lon)) {
        return {
            esDuplicado: false,
            puntaje: 1.0,
            reportesCercanos: [],
            mensaje: 'Omitida consulta de duplicados (sin coordenadas válidas o pool)'
        };
    }

    try {
        const query = `
            SELECT id, ticket, latitud, longitud, categoria, estado, barrio, direccion_referencia, fecha_creacion
            FROM denuncias
            WHERE estado IN ('pendiente', 'en_proceso')
              AND fecha_creacion >= NOW() - ($1 || ' HOURS')::INTERVAL
        `;
        const res = await pool.query(query, [config.duplicateWindowHours]);

        const cercanos = [];
        for (const fila of res.rows) {
            const fLat = parseFloat(fila.latitud);
            const fLon = parseFloat(fila.longitud);
            if (!isNaN(fLat) && !isNaN(fLon)) {
                const dist = calcularDistanciaMetros(lat, lon, fLat, fLon);
                if (dist <= config.duplicateRadiusMeters) {
                    cercanos.push({
                        id: fila.id,
                        ticket: fila.ticket,
                        distanciaMetros: Math.round(dist),
                        categoria: fila.categoria,
                        estado: fila.estado,
                        barrio: fila.barrio,
                        direccion: fila.direccion_referencia
                    });
                }
            }
        }

        const esDuplicado = cercanos.length > 0;
        const puntaje = esDuplicado ? 0.25 : 1.0;

        return {
            esDuplicado,
            puntaje,
            reportesCercanos: cercanos,
            totalCercanos: cercanos.length
        };
    } catch (err) {
        console.warn('⚠️ Error al consultar duplicados en base de datos:', err.message);
        return {
            esDuplicado: false,
            puntaje: 0.9,
            reportesCercanos: [],
            error: err.message
        };
    }
}

/**
 * Evalúa integralmente una denuncia emitiendo veredicto, puntuación y estado sugerido
 */
async function evaluarDenunciaCompleta(datos, pool) {
    const evalTextoFoto = await evaluarCoherenciaTextoFoto(datos);
    const evalGeo = evaluarGeovalidacion(datos);
    const evalDuplicados = await evaluarDuplicadosYSpam(datos, pool);

    // Auditoría visual profunda con Gemini Vision si hay imagen
    let evalGemini = { ejecutado: false };
    try {
        evalGemini = await validarDenunciaConGemini(datos);
        if (evalGemini.ejecutado) {
            console.log(`🤖 [Gemini Vision]: es_valida=${evalGemini.es_valida}, es_falsa=${evalGemini.es_falsa_denuncia}, confianza=${evalGemini.confianza}`);
        }
    } catch (gErr) {
        console.warn('⚠️ Error ejecutando Gemini Vision:', gErr.message);
    }

    // Ponderación de pesos para confianza global
    let puntosGemini = evalTextoFoto.puntaje;
    if (evalGemini.ejecutado) {
        puntosGemini = evalGemini.es_falsa_denuncia ? 0.05 : (evalGemini.confianza || 0.95);
    }

    // Geo: 30%, Texto-Foto/Gemini: 45%, Duplicados/Spam: 25%
    const puntuacionConfianza = parseFloat(
        (evalGeo.puntaje * 0.30 + puntosGemini * 0.45 + evalDuplicados.puntaje * 0.25).toFixed(2)
    );

    const motivos = [];
    let veredicto = 'VALIDA';
    let estadoAsignado = 'pendiente';

    // 1. Verificación de Falsa Denuncia detectada por Gemini Vision
    if (evalGemini.ejecutado) {
        if (evalGemini.es_falsa_denuncia || !evalGemini.es_valida) {
            veredicto = 'RECHAZADA';
            estadoAsignado = 'rechazada';
            const motivoG = evalGemini.motivo_rechazo || evalGemini.justificacion || 'La foto no evidencia residuos ni acumulación de basura';
            motivos.push(`FALSA DENUNCIA detectada por IA (Gemini Vision): ${motivoG}`);
        }
    }

    // 2. Reglas de descarte / bloqueo crítico geográfico
    if (!evalGeo.dentroDeLimites) {
        veredicto = 'RECHAZADA';
        estadoAsignado = 'rechazada';
        motivos.push('Ubicación fuera de la jurisdicción municipal permitida de Asunción');
    }

    // 3. Reglas de coherencia de texto si no fue rechazada previamente
    if (!evalTextoFoto.valido && veredicto !== 'RECHAZADA') {
        veredicto = 'RECHAZADA';
        estadoAsignado = 'rechazada';
        motivos.push(...evalTextoFoto.problemas);
    }

    // 4. Detección de duplicados
    if (evalDuplicados.esDuplicado) {
        const primerDuplicado = evalDuplicados.reportesCercanos[0];
        motivos.push(`Posible reporte duplicado a ${primerDuplicado.distanciaMetros}m del ticket activo ${primerDuplicado.ticket}`);
        if (veredicto !== 'RECHAZADA') {
            veredicto = 'SOSPECHOSA';
            estadoAsignado = 'requiere_revision';
        }
    }

    // 5. Umbral de confianza
    if (veredicto === 'VALIDA' && puntuacionConfianza < config.minConfidence) {
        veredicto = 'SOSPECHOSA';
        estadoAsignado = 'requiere_revision';
        motivos.push(`Puntuación de confianza (${puntuacionConfianza}) inferior al umbral mínimo (${config.minConfidence})`);
    }

    let observaciones = '';
    if (veredicto === 'VALIDA') {
        const extraGemini = evalGemini.ejecutado ? ` [IA Gemini: ${evalGemini.justificacion}]` : '';
        observaciones = `Aprobado por Agente EcoRuta (Confianza: ${(puntuacionConfianza * 100).toFixed(0)}%). Evidencia visual y geoespacial validada.${extraGemini}`;
    } else {
        observaciones = `Filtrado por Agente EcoRuta [${veredicto}]: ${motivos.join('; ')}`;
    }

    return {
        veredicto,
        puntuacionConfianza,
        estadoAsignado,
        observaciones,
        motivos,
        metricas: {
            coherenciaTextoFoto: evalTextoFoto,
            geovalidacion: evalGeo,
            antiSpamDuplicados: evalDuplicados,
            geminiVision: evalGemini
        }
    };
}

module.exports = {
    calcularDistanciaMetros,
    evaluarCoherenciaTextoFoto,
    evaluarGeovalidacion,
    evaluarDuplicadosYSpam,
    evaluarDenunciaCompleta
};
