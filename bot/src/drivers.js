const config = require('./config');

/**
 * Registro en memoria de conductores vinculados por Telegram Chat ID.
 * Se inicializa con el conductor de pruebas solicitado en los requerimientos.
 */
const driversRegistry = new Map();

// Registrar conductor de pruebas por defecto
if (config.testDriverTelegramId) {
    driversRegistry.set(config.testDriverTelegramId, {
        telegramId: config.testDriverTelegramId,
        nombre: 'Conductor Demo',
        zona: config.defaultZone,
        vehiculo: 'Camión Recolector #01',
        activo: true,
        fechaRegistro: new Date().toISOString()
    });
}

/**
 * Obtiene el perfil del conductor a partir de su Telegram Chat ID
 */
function getDriverByTelegramId(telegramId) {
    return driversRegistry.get(String(telegramId).trim()) || null;
}

/**
 * Busca el primer conductor activo asignado a una zona específica
 * Si no encuentra uno exacto, devuelve el conductor de pruebas como fallback
 */
function getDriverByZone(zone) {
    if (!zone) return driversRegistry.get(config.testDriverTelegramId) || null;

    const zonaNormalizada = zone.trim().toLowerCase();
    for (const driver of driversRegistry.values()) {
        if (driver.activo && driver.zona.trim().toLowerCase() === zonaNormalizada) {
            return driver;
        }
    }

    // Fallback: Si no hay conductor asignado a la zona puntual, despachar al de pruebas
    return driversRegistry.get(config.testDriverTelegramId) || null;
}

/**
 * Registra o actualiza el estado de un conductor
 */
function registerOrUpdateDriver(telegramId, data = {}) {
    const id = String(telegramId).trim();
    const existing = driversRegistry.get(id) || {};

    const updated = {
        telegramId: id,
        nombre: data.nombre || existing.nombre || `Conductor (${id})`,
        zona: data.zona || existing.zona || config.defaultZone,
        vehiculo: data.vehiculo || existing.vehiculo || 'Camión Recolector Asignado',
        activo: data.activo !== undefined ? data.activo : true,
        fechaActualizacion: new Date().toISOString()
    };

    driversRegistry.set(id, updated);
    return updated;
}

/**
 * Retorna todos los conductores registrados
 */
function getAllDrivers() {
    return Array.from(driversRegistry.values());
}

module.exports = {
    getDriverByTelegramId,
    getDriverByZone,
    registerOrUpdateDriver,
    getAllDrivers
};
