/**
 * Reglas de negocio y taxonomía estándar del ecosistema Ecoruta
 */

// Categorías estándar aprobadas por Ecoruta
const CATEGORIAS_VALIDAS = {
    VERTEDERO_CLANDESTINO: {
        codigo: 'VERTEDERO_CLANDESTINO',
        nombre: 'Vertedero Clandestino / Acumulación Masiva',
        descripcion: 'Montículos de basura arrojados ilegalmente en veredas, baldíos o esquinas.',
        palabrasClave: ['vertedero', 'basural', 'escombros', 'bolsas', 'acumulacion', 'desechos', 'inmundicia', 'calle', 'baldío', 'esquina', 'plaza', 'desperdicios']
    },
    CONTENEDOR_LLENO: {
        codigo: 'CONTENEDOR_LLENO',
        nombre: 'Contenedor Lleno o Desbordado',
        descripcion: 'Contenedor municipal saturado de residuos desbordando en la acera.',
        palabrasClave: ['contenedor', 'colapsado', 'desbordado', 'lleno', 'tacho', 'caja', 'capacidad', 'recolector', 'vereda']
    },
    RESIDUO_VOLUMINOSO: {
        codigo: 'RESIDUO_VOLUMINOSO',
        nombre: 'Residuo Voluminoso / Chatarra',
        descripcion: 'Muebles, colchones, electrodomésticos en desuso o chatarra metálica.',
        palabrasClave: ['voluminoso', 'mueble', 'colchon', 'chatarra', 'heladera', 'hierro', 'electrodomestico', 'sillon', 'madera', 'fierro', 'cama']
    },
    RESTOS_PODA: {
        codigo: 'RESTOS_PODA',
        nombre: 'Restos de Poda y Ramas',
        descripcion: 'Ramas, troncos, hojas y restos de jardinería acumulados en vía pública.',
        palabrasClave: ['poda', 'ramas', 'hojas', 'arbol', 'tronco', 'jardineria', 'arbusto', 'maleza', 'corte']
    },
    ANIMAL_MUERTO: {
        codigo: 'ANIMAL_MUERTO',
        nombre: 'Animal Muerto en Vía Pública',
        descripcion: 'Carcasa o animal muerto que requiere retiro sanitario urgente.',
        palabrasClave: ['animal', 'perro', 'gato', 'caballo', 'vaca', 'muerto', 'carcasa', 'olor', 'putrefaccion', 'sanitario', 'descompuesto']
    }
};

// Términos generales del dominio de limpieza y recolección
const TERMINOS_GENERALES_RESIDUOS = [
    'basura', 'residuos', 'desechos', 'limpieza', 'olor', 'moscas', 'contaminacion',
    'desperdicio', 'vereda', 'calle', 'avenida', 'camion', 'recoleccion', 'tirado', 'arrojado'
];

// Patrones de spam y falsedad obvios
const PATRONES_SPAM = [
    /vendo\s+/i,
    /compra\s+/i,
    /prestamos?/i,
    /tarjeta\s+de\s+credito/i,
    /casino|apuesta|gane\s+dinero/i,
    /crypto|bitcoin|inversion/i,
    /celulares?\s+baratos?/i,
    /https?:\/\/(?!images\.unsplash\.com|res\.cloudinary\.com|i\.imgur\.com)[^\s]+/i,
    /^[a-z0-9]{1,4}$/i, // texto extremadamente corto sin sentido
    /(.)\1{4,}/i,       // repetición de letras como 'aaaaaa' o 'xxxxxx'
    /asdf|qwer|zxcv|12345/i // tecleo aleatorio
];

// Barrios oficiales de Asunción y Gran Asunción para geovalidación semántica
const BARRIOS_JURISDICCION = [
    'Sajonia', 'Centro', 'Centro Histórico', 'San Roque', 'La Encarnación', 'Catedral',
    'Las Mercedes', 'Ciudad Nueva', 'Mburicao', 'Recoleta', 'Villa Morra', 'Ycua Sati',
    'Manora', 'Trinidad', 'Zeballos Cué', 'Loma Pytá', 'Botánico', 'San Pablo', 'Hipódromo',
    'Pinozá', 'Vista Alegre', 'Tacumbú', 'Obrero', 'Roberto L. Petit', 'Bañado Sur',
    'Bañado Norte', 'Santa Librada', 'San Vicente', 'Nazareth', 'Terminal', 'Santa María',
    'Zona Centro', 'Microcentro', 'Fernando de la Mora', 'Lambaré', 'Luque', 'San Lorenzo'
];

module.exports = {
    CATEGORIAS_VALIDAS,
    TERMINOS_GENERALES_RESIDUOS,
    PATRONES_SPAM,
    BARRIOS_JURISDICCION
};
