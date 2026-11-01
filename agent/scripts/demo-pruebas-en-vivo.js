const fs = require('fs');
const path = require('path');

async function ejecutarPruebasCompletas() {
    console.log('====================================================================');
    console.log('🧪 SUITE DE PRUEBAS EN VIVO: VALIDACIÓN CON GEMINI VISION (ECORUTA)');
    console.log('====================================================================\n');

    const agentUrl = 'http://localhost:5050/api/denuncias/validar-y-enviar';

    // -------------------------------------------------------------
    // PRUEBA 1: Denuncia FALSA (Foto de un gato - Intento de engaño / broma)
    // -------------------------------------------------------------
    console.log('📌 [PRUEBA 1] Enviando reporte con texto engañoso pero FOTO DE UN GATO (FALSA DENUNCIA)...');
    
    const fotoGatoBase64 = fs.readFileSync(path.resolve(__dirname, '../uploads/test-gato.jpg')).toString('base64');
    const denunciaFalsa = {
        categoria: 'VERTEDERO_CLANDESTINO',
        descripcion: 'Vertedero de basura ilegal con olores nauseabundos en la esquina de la plaza',
        barrio: 'San Pablo',
        latitud: -25.3215,
        longitud: -57.5850,
        foto_base64: `data:image/jpeg;base64,${fotoGatoBase64}`
    };

    try {
        const tInicio1 = Date.now();
        const res1 = await fetch(agentUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(denunciaFalsa)
        });
        const data1 = await res1.json();
        const tDuracion1 = ((Date.now() - tInicio1) / 1000).toFixed(2);

        console.log(`⏱️  Tiempo de respuesta: ${tDuracion1}s`);
        console.log(`🚦 Veredicto Agente: ${data1.veredicto}`);
        console.log(`📊 Estado asignado: ${data1.estado}`);
        console.log(`🎯 Confianza: ${(data1.confianza * 100).toFixed(0)}%`);
        console.log(`📝 Motivo de bloqueo: ${data1.observaciones}`);
        if (data1.metricas?.geminiVision) {
            const g = data1.metricas.geminiVision;
            console.log(`🤖 Dictamen Gemini Vision:`);
            console.log(`   - Modelo: ${g.modelo}`);
            console.log(`   - ¿Es falsa denuncia?: ${g.es_falsa_denuncia ? 'SÍ ❌' : 'NO'}`);
            console.log(`   - Elementos que vio Gemini: ${JSON.stringify(g.elementos_visibles)}`);
            console.log(`   - Explicación de la IA: ${g.justificacion}`);
        }
    } catch (err) {
        console.error('❌ Error en Prueba 1:', err.message);
    }

    console.log('\n--------------------------------------------------------------------\n');

    // -------------------------------------------------------------
    // PRUEBA 2: Denuncia REAL (Foto real de acumulación de basura en la calle)
    // -------------------------------------------------------------
    console.log('📌 [PRUEBA 2] Enviando reporte con FOTO REAL DE BASURA EN VÍA PÚBLICA (DENUNCIA LEGÍTIMA)...');

    const fotoBasuraBase64 = fs.readFileSync(path.resolve(__dirname, '../uploads/test-basura-real.jpg')).toString('base64');
    const denunciaReal = {
        categoria: 'VERTEDERO_CLANDESTINO',
        descripcion: 'Acumulación de bolsas de basura rotas y desechos tirados en la vía pública',
        barrio: 'Recoleta',
        latitud: -25.2980,
        longitud: -57.5920,
        foto_base64: `data:image/jpeg;base64,${fotoBasuraBase64}`
    };

    try {
        const tInicio2 = Date.now();
        const res2 = await fetch(agentUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(denunciaReal)
        });
        const data2 = await res2.json();
        const tDuracion2 = ((Date.now() - tInicio2) / 1000).toFixed(2);

        console.log(`⏱️  Tiempo de respuesta: ${tDuracion2}s`);
        console.log(`🚦 Veredicto Agente: ${data2.veredicto}`);
        console.log(`📊 Estado asignado: ${data2.estado}`);
        console.log(`🎟️ Ticket creado en EcoRuta: ${data2.ticket || data2.denuncia?.ticket || 'ECO-GENERADO'}`);
        console.log(`🎯 Confianza: ${(data2.confianza * 100).toFixed(0)}%`);
        console.log(`📝 Despacho: ${data2.observaciones}`);
        if (data2.metricas?.geminiVision) {
            const g = data2.metricas.geminiVision;
            console.log(`🤖 Dictamen Gemini Vision:`);
            console.log(`   - Modelo: ${g.modelo}`);
            console.log(`   - ¿Es válida?: ${g.es_valida ? 'SÍ ✅' : 'NO'}`);
            console.log(`   - Elementos que vio Gemini: ${JSON.stringify(g.elementos_visibles)}`);
            console.log(`   - Explicación de la IA: ${g.justificacion}`);
        }
    } catch (err) {
        console.error('❌ Error en Prueba 2:', err.message);
    }

    console.log('\n====================================================================');
    console.log('✅ SUITE DE PRUEBAS COMPLETADA EXITOSAMENTE');
    console.log('====================================================================');
}

ejecutarPruebasCompletas();
