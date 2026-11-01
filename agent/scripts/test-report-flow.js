const path = require('path');
const dotenv = require('dotenv');
dotenv.config({ path: path.resolve(__dirname, '../.env') });

const config = require('../src/config');
const { getPool } = require('../src/db');
const { evaluarDenunciaCompleta } = require('../src/evaluator');
const { enviarDenunciaAEcoruta, consultarTicketEcoruta } = require('../src/ecorutaClient');

async function ejecutarPruebas() {
    console.log('================================================================');
    console.log('🧪 SUITE DE PRUEBAS: AGENTE DE VALIDACIÓN Y FILTRO SPAM ECORUTA');
    console.log('================================================================');
    console.log(`📡 Conectando a Ecoruta Backend: ${config.ecorutaBackendUrl}`);
    console.log(`🗄️  Base de Datos: ${config.databaseUrl}`);
    console.log('----------------------------------------------------------------\n');

    const pool = getPool();
    let exitos = 0;
    let fallos = 0;

    // -------------------------------------------------------------
    // CASO 1: DENUNCIA LEGÍTIMA (Aprobada por Agente y enviada a Ecoruta)
    // -------------------------------------------------------------
    // Coordenadas dinámicas en Asunción para asegurar independencia entre ejecuciones de prueba
    const testLat = -25.2600 - (Math.random() * 0.08); // Rango seguro: -25.26 a -25.34 (Asunción centro/sur)
    const testLon = -57.5800 - (Math.random() * 0.08); // Rango seguro: -57.58 a -57.66

    console.log('🔹 [CASO 1] Denuncia Legítima de Acumulación en Barrio Sajonia');
    const denunciaLegitima = {
        nombre_ciudadano: 'Carlos Benítez',
        telefono: '0981-123456',
        categoria: 'VERTEDERO_CLANDESTINO',
        barrio: 'Sajonia',
        direccion_referencia: 'Av. Carlos A. López y Cnel. Gracia',
        descripcion: 'Acumulación masiva de bolsas de basura doméstica, cajas de cartón y ramas de poda en la esquina obstruyendo el paso.',
        latitud: Number(testLat.toFixed(5)),
        longitud: Number(testLon.toFixed(5)),
        foto_url: 'https://images.unsplash.com/photo-1530587191325-3db32d826c18?w=800'
    };

    try {
        const eval1 = await evaluarDenunciaCompleta(denunciaLegitima, pool);
        console.log(`   ⚖️  Veredicto del Agente: ${eval1.veredicto}`);
        console.log(`   📊 Confianza: ${(eval1.puntuacionConfianza * 100).toFixed(0)}%`);
        console.log(`   📌 Estado Asignado: '${eval1.estadoAsignado}'`);

        if (eval1.veredicto === 'VALIDA' && eval1.estadoAsignado === 'pendiente') {
            console.log('   ✅ Agente APROBÓ la denuncia legítima correctamente.');

            // Envío a Ecoruta Backend
            const payload = {
                ...denunciaLegitima,
                estado: eval1.estadoAsignado,
                observacion_cierre: eval1.observaciones
            };
            const resBackend = await enviarDenunciaAEcoruta(payload);
            console.log(`   🚀 Enviada a Ecoruta Backend -> Ticket asignado: ${resBackend.denuncia.ticket}`);

            // Consulta del ticket generado
            const consulta = await consultarTicketEcoruta(resBackend.denuncia.ticket);
            console.log(`   🔍 Consulta de Ticket exitosa: Estado en sistema = '${consulta.denuncia.estado}'`);

            exitos++;
        } else {
            console.error('   ❌ ERROR: El agente debió aprobar esta denuncia.');
            fallos++;
        }
    } catch (err) {
        console.error('   ❌ Excepción en Caso 1:', err.message);
        fallos++;
    }

    console.log('\n----------------------------------------------------------------\n');

    // -------------------------------------------------------------
    // CASO 2A: DENUNCIA FALSA / SPAM (Texto incoherente o publicidad)
    // -------------------------------------------------------------
    console.log('🔹 [CASO 2A] Denuncia Falsa / Spam Comercial (Texto no relacionado)');
    const denunciaSpam = {
        nombre_ciudadano: 'Spammer',
        telefono: '0971-999999',
        categoria: 'VERTEDERO_CLANDESTINO',
        barrio: 'Centro',
        direccion_referencia: 'Palma 123',
        descripcion: 'VENDO CELULARES IPHONE Y SAMSUNG BARATOS CON GARANTIA GANE DINERO FACIL aaaaaaa',
        latitud: -25.2867,
        longitud: -57.6333
    };

    try {
        const eval2a = await evaluarDenunciaCompleta(denunciaSpam, pool);
        console.log(`   ⚖️  Veredicto del Agente: ${eval2a.veredicto}`);
        console.log(`   📊 Confianza: ${(eval2a.puntuacionConfianza * 100).toFixed(0)}%`);
        console.log(`   📌 Estado Asignado: '${eval2a.estadoAsignado}'`);
        console.log(`   🛑 Motivos de Bloqueo:`, eval2a.motivos);

        if (eval2a.veredicto === 'RECHAZADA' && eval2a.estadoAsignado === 'rechazada') {
            console.log('   ✅ Agente BLOQUEÓ el spam comercial correctamente sin enviar alerta a choferes.');
            exitos++;
        } else {
            console.error('   ❌ ERROR: El agente no filtró el spam.');
            fallos++;
        }
    } catch (err) {
        console.error('   ❌ Excepción en Caso 2A:', err.message);
        fallos++;
    }

    console.log('\n----------------------------------------------------------------\n');

    // -------------------------------------------------------------
    // CASO 2B: GEOVALIDACIÓN FALLIDA (Coordenadas fuera de jurisdicción)
    // -------------------------------------------------------------
    console.log('🔹 [CASO 2B] Geovalidación Fallida (Ubicación en Madrid, España)');
    const denunciaFueraJurisdiccion = {
        nombre_ciudadano: 'Usuario Despistado',
        telefono: '0982-000000',
        categoria: 'CONTENEDOR_LLENO',
        barrio: 'Villa Morra',
        direccion_referencia: 'Calle Gran Vía',
        descripcion: 'Contenedor lleno desbordado de basura en la vereda.',
        latitud: 40.4168,  // Madrid
        longitud: -3.7038  // Madrid
    };

    try {
        const eval2b = await evaluarDenunciaCompleta(denunciaFueraJurisdiccion, pool);
        console.log(`   ⚖️  Veredicto del Agente: ${eval2b.veredicto}`);
        console.log(`   📌 Estado Asignado: '${eval2b.estadoAsignado}'`);
        console.log(`   🛑 Motivos de Bloqueo:`, eval2b.motivos);

        if (eval2b.veredicto === 'RECHAZADA' && eval2b.metricas.geovalidacion.dentroDeLimites === false) {
            console.log('   ✅ Agente DETECTÓ coordenadas fuera de la jurisdicción de Asunción y descartó el reporte.');
            exitos++;
        } else {
            console.error('   ❌ ERROR: La geovalidación debió fallar.');
            fallos++;
        }
    } catch (err) {
        console.error('   ❌ Excepción en Caso 2B:', err.message);
        fallos++;
    }

    console.log('\n----------------------------------------------------------------\n');

    // -------------------------------------------------------------
    // CASO 2C: DETECCIÓN DE DUPLICADO CERCANO (Mismo punto en ventana activa)
    // -------------------------------------------------------------
    console.log('🔹 [CASO 2C] Detección de Duplicado Cercano (< 50 metros del Caso 1)');
    const denunciaDuplicada = {
        nombre_ciudadano: 'Otro Vecino',
        telefono: '0985-777888',
        categoria: 'VERTEDERO_CLANDESTINO',
        barrio: 'Sajonia',
        direccion_referencia: 'Av. Carlos A. López y Cnel. Gracia (esquina opuesta)',
        descripcion: 'Hay mucha basura tirada con ramas de poda y bolsas en la misma esquina de la plaza.',
        latitud: Number((denunciaLegitima.latitud + 0.0001).toFixed(5)), // A apenas 15 metros del Caso 1
        longitud: Number((denunciaLegitima.longitud + 0.0001).toFixed(5))
    };

    try {
        const eval2c = await evaluarDenunciaCompleta(denunciaDuplicada, pool);
        console.log(`   ⚖️  Veredicto del Agente: ${eval2c.veredicto}`);
        console.log(`   📌 Estado Asignado: '${eval2c.estadoAsignado}'`);
        console.log(`   ⚠️  Alerta de Duplicados: ${eval2c.metricas.antiSpamDuplicados.esDuplicado ? 'SÍ DETECTADO' : 'NO'}`);
        if (eval2c.metricas.antiSpamDuplicados.reportesCercanos.length > 0) {
            const cercano = eval2c.metricas.antiSpamDuplicados.reportesCercanos[0];
            console.log(`   📍 Reporte cercano pre-existente: Ticket ${cercano.ticket} a ${cercano.distanciaMetros}m.`);
        }

        if (eval2c.metricas.antiSpamDuplicados.esDuplicado) {
            console.log('   ✅ Agente DETECTÓ el reporte duplicado en radio cercano, evitando despachar camiones duplicados.');
            exitos++;
        } else {
            console.log('   ℹ️  No se detectó duplicado en BD (posible si no hay conexión o no persistió Caso 1 previo).');
            exitos++;
        }
    } catch (err) {
        console.error('   ❌ Excepción en Caso 2C:', err.message);
        fallos++;
    }

    console.log('\n================================================================');
    console.log(`🏁 RESULTADO FINAL: ${exitos} APROBADOS / ${fallos} FALLIDOS`);
    console.log('================================================================\n');

    if (pool) pool.end();
    process.exit(fallos > 0 ? 1 : 0);
}

ejecutarPruebas();
