const fs = require('fs');
const path = require('path');
const config = require('./config');

/**
 * Obtiene el buffer y mime-type de una imagen (sea archivo local, base64 o URL)
 */
async function obtenerImagenBase64(datos) {
    // 1. Archivo local guardado por multer o ruta directa
    if (datos.foto_archivo_local && fs.existsSync(datos.foto_archivo_local)) {
        const ext = path.extname(datos.foto_archivo_local).toLowerCase();
        let mime = 'image/jpeg';
        if (ext === '.png') mime = 'image/png';
        if (ext === '.webp') mime = 'image/webp';
        const buffer = fs.readFileSync(datos.foto_archivo_local);
        return { base64: buffer.toString('base64'), mime };
    }

    // 2. Base64 directo
    if (datos.foto_base64 && typeof datos.foto_base64 === 'string') {
        const match = datos.foto_base64.match(/^data:(image\/\w+);base64,(.+)$/);
        if (match) {
            return { mime: match[1], base64: match[2] };
        }
        return { mime: 'image/jpeg', base64: datos.foto_base64 };
    }

    // 3. Foto URL local (ej. http://localhost:.../uploads/...) o externa
    if (datos.foto_url && typeof datos.foto_url === 'string') {
        // Si es una URL de uploads local
        const uploadsMatch = datos.foto_url.match(/\/uploads\/([^/?#]+)/);
        if (uploadsMatch) {
            const localFile = path.resolve(__dirname, '../uploads', uploadsMatch[1]);
            if (fs.existsSync(localFile)) {
                const ext = path.extname(localFile).toLowerCase();
                let mime = ext === '.png' ? 'image/png' : (ext === '.webp' ? 'image/webp' : 'image/jpeg');
                const buffer = fs.readFileSync(localFile);
                return { base64: buffer.toString('base64'), mime };
            }
        }

        // Descarga externa por HTTP si es remota
        if (/^https?:\/\//i.test(datos.foto_url)) {
            try {
                const res = await fetch(datos.foto_url, { signal: AbortSignal.timeout(6000) });
                if (res.ok) {
                    const arrayBuf = await res.arrayBuffer();
                    const mime = res.headers.get('content-type') || 'image/jpeg';
                    return { base64: Buffer.from(arrayBuf).toString('base64'), mime };
                }
            } catch (err) {
                console.warn('⚠️ No se pudo descargar imagen externa para Gemini:', err.message);
            }
        }
    }

    return null;
}

/**
 * Analiza la foto y la coherencia de la denuncia utilizando Gemini 2.5 Flash
 */
async function validarDenunciaConGemini(datos) {
    const apiKey = config.geminiApiKey;
    if (!apiKey) {
        return {
            ejecutado: false,
            motivo: 'GEMINI_API_KEY no configurada'
        };
    }

    const imgData = await obtenerImagenBase64(datos);
    if (!imgData) {
        return {
            ejecutado: false,
            motivo: 'No se suministró imagen válida para análisis visual'
        };
    }

    const prompt = `
Eres el agente perito de validación de denuncias ciudadanas de recolección de residuos urbanos para el sistema EcoRuta.
Audita la imagen adjunta para determinar si es una denuncia legítima o una FALSA DENUNCIA / SPAM / BROMA / FOTO IRRELEVANTE.

Datos reportados por el ciudadano:
- Categoría denunciada: "${datos.categoria || 'VERTEDERO_CLANDESTINO'}"
- Descripción: "${datos.descripcion || ''}"
- Barrio / Ubicación: "${datos.barrio || 'Sin especificar'}"

CRITERIOS DE FALSA DENUNCIA / RECHAZO:
1. La imagen NO muestra residuos, basura, escombros, ramas de poda, contenedores o animales muertos (ej: selfies, personas, fotos de mascotas vivas, capturas de pantalla de chats/redes, memes, fotos completamente negras o borrosas, calles totalmente limpias sin desperfectos).
2. La imagen contradice flagrantemente la descripción o busca burlar el sistema.
3. No existe necesidad real de enviar un camión o cuadrilla de recolección.

CRITERIOS DE DENUNCIA VÁLIDA:
1. Se observan residuos sólidos, bolsas de basura amontonadas, vertederos clandestinos, contenedores rebasados, restos de poda o escombros en vía pública o predios.
2. La denuncia es creíble y amerita recolección municipal.

Responde ÚNICAMENTE en JSON con el siguiente esquema estricto:
{
  "es_falsa_denuncia": boolean,
  "es_valida": boolean,
  "confianza": number, // entre 0.0 y 1.0
  "categoria_detectada": string, // "VERTEDERO_CLANDESTINO", "CONTENEDOR_LLENO", "RESIDUO_VOLUMINOSO", "RESTOS_PODA", "ANIMAL_MUERTO", u "OTRO"
  "elementos_visibles": string[],
  "justificacion": string, // explicación concisa de lo observado
  "motivo_rechazo": string | null // si es falsa denuncia, explicar brevemente por qué
}
`;

    const modelosCandidatos = [
        'gemini-3.1-flash-lite',
        'gemini-flash-lite-latest',
        'gemini-3.8-flash'
    ];

    const requestBody = {
        contents: [
            {
                role: 'user',
                parts: [
                    { text: prompt },
                    {
                        inline_data: {
                            mime_type: imgData.mime,
                            data: imgData.base64
                        }
                    }
                ]
            }
        ],
        generationConfig: {
            temperature: 0.1,
            response_mime_type: 'application/json'
        }
    };

    let ultimoError = null;
    for (const modelo of modelosCandidatos) {
        try {
            const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${modelo}:generateContent?key=${apiKey}`;
            const res = await fetch(endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requestBody),
                signal: AbortSignal.timeout(10000)
            });

            if (!res.ok) {
                const errText = await res.text();
                ultimoError = `HTTP ${res.status} (${modelo}): ${errText}`;
                continue; // intentar siguiente modelo
            }

            const data = await res.json();
            const rawJsonText = data.candidates?.[0]?.content?.parts?.[0]?.text;
            if (!rawJsonText) {
                ultimoError = `Respuesta vacía de ${modelo}`;
                continue;
            }

            const parsed = JSON.parse(rawJsonText);
            return {
                ejecutado: true,
                modelo,
                ...parsed
            };
        } catch (err) {
            ultimoError = `${modelo}: ${err.message}`;
        }
    }

    console.error('❌ Error en consulta a Gemini Vision:', ultimoError);
    return {
        ejecutado: false,
        error: ultimoError
    };
}

module.exports = {
    validarDenunciaConGemini,
    obtenerImagenBase64
};
