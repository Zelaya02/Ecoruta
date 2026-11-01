const path = require('path');
const { validarDenunciaConGemini } = require('../src/geminiVision');

async function test() {
    console.log('🧪 Iniciando prueba de Gemini Vision en EcoRuta...');

    // Imagen 1x1 pixel blanco en base64 (no contiene basura)
    const falsaFotoBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

    const denunciaFalsa = {
        categoria: 'VERTEDERO_CLANDESTINO',
        descripcion: 'Hay un vertedero clandestino gigante en la esquina',
        barrio: 'San Pablo',
        foto_base64: falsaFotoBase64
    };

    console.log('\n--- Probando detección de Falsa Denuncia (imagen vacía/blanca) ---');
    const resFalsa = await validarDenunciaConGemini(denunciaFalsa);
    console.log('Resultado Gemini:', JSON.stringify(resFalsa, null, 2));

    if (resFalsa.ejecutado) {
        if (resFalsa.es_falsa_denuncia === true || !resFalsa.es_valida) {
            console.log('✅ ¡ÉXITO! Gemini detectó correctamente que es una falsa denuncia sin residuos.');
        } else {
            console.log('ℹ️ Gemini respondió:', resFalsa.justificacion);
        }
    } else {
        console.error('❌ Error al ejecutar Gemini:', resFalsa.error);
    }
}

test();
