/**
 * EcoRuta Glassmorphism Dialogs & Toasts Suite
 * Reemplazo moderno y elegante para alert(), confirm() y prompt()
 */
(function() {
    'use strict';

    // 1. Inyección de estilos CSS Glassmorphism
    if (!document.getElementById('eco-glass-dialogs-css')) {
        const style = document.createElement('style');
        style.id = 'eco-glass-dialogs-css';
        style.textContent = `
            /* Contenedor de Toasts */
            .eco-toast-container {
                position: fixed;
                top: 24px;
                right: 24px;
                z-index: 999999;
                display: flex;
                flex-direction: column;
                gap: 10px;
                pointer-events: none;
                max-width: 380px;
                width: calc(100vw - 48px);
            }

            .eco-toast {
                pointer-events: auto;
                background: rgba(14, 28, 22, 0.88);
                backdrop-filter: blur(20px) saturate(180%);
                -webkit-backdrop-filter: blur(20px) saturate(180%);
                border: 1px solid rgba(255, 255, 255, 0.12);
                border-radius: 14px;
                padding: 14px 18px;
                color: #f8fafc;
                box-shadow: 0 16px 40px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.15);
                display: flex;
                align-items: center;
                gap: 14px;
                font-family: 'Inter', system-ui, -apple-system, sans-serif;
                font-size: 13px;
                line-height: 1.45;
                animation: ecoToastSlideIn 0.35s cubic-bezier(0.16, 1, 0.3, 1) forwards;
                transition: opacity 0.25s ease, transform 0.25s ease;
                cursor: pointer;
            }

            .eco-toast.hiding {
                opacity: 0;
                transform: translateX(30px) scale(0.95);
            }

            .eco-toast-icon {
                width: 32px;
                height: 32px;
                min-width: 32px;
                border-radius: 10px;
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 15px;
            }

            .eco-toast-success { border-left: 3px solid #10b981; }
            .eco-toast-success .eco-toast-icon { background: rgba(16, 185, 129, 0.2); color: #7dffc4; }

            .eco-toast-danger { border-left: 3px solid #ef4444; }
            .eco-toast-danger .eco-toast-icon { background: rgba(239, 68, 68, 0.2); color: #fca5a5; }

            .eco-toast-warning { border-left: 3px solid #f59e0b; }
            .eco-toast-warning .eco-toast-icon { background: rgba(245, 158, 11, 0.2); color: #fde68a; }

            .eco-toast-info { border-left: 3px solid #3b82f6; }
            .eco-toast-info .eco-toast-icon { background: rgba(59, 130, 246, 0.2); color: #93c5fd; }

            .eco-toast-content {
                flex: 1;
                font-weight: 500;
                word-break: break-word;
            }

            .eco-toast-close {
                background: transparent;
                border: none;
                color: rgba(255, 255, 255, 0.4);
                cursor: pointer;
                font-size: 16px;
                padding: 4px;
                line-height: 1;
                border-radius: 6px;
                transition: all 0.15s ease;
            }
            .eco-toast-close:hover {
                color: #ffffff;
                background: rgba(255, 255, 255, 0.1);
            }

            @keyframes ecoToastSlideIn {
                from { opacity: 0; transform: translateX(40px) scale(0.92); }
                to { opacity: 1; transform: translateX(0) scale(1); }
            }

            /* Overlay Modal Glassmorphism */
            .eco-modal-backdrop {
                position: fixed;
                inset: 0;
                z-index: 1000000;
                background: rgba(6, 17, 12, 0.72);
                backdrop-filter: blur(16px) saturate(160%);
                -webkit-backdrop-filter: blur(16px) saturate(160%);
                display: flex;
                align-items: center;
                justify-content: center;
                padding: 20px;
                opacity: 0;
                pointer-events: none;
                transition: opacity 0.25s ease;
                font-family: 'Inter', system-ui, -apple-system, sans-serif;
            }

            .eco-modal-backdrop.active {
                opacity: 1;
                pointer-events: auto;
            }

            .eco-modal-card {
                background: rgba(15, 32, 25, 0.92);
                backdrop-filter: blur(24px) saturate(180%);
                -webkit-backdrop-filter: blur(24px) saturate(180%);
                border: 1px solid rgba(255, 255, 255, 0.15);
                border-radius: 20px;
                padding: 26px;
                max-width: 440px;
                width: 100%;
                box-shadow: 0 25px 60px rgba(0, 0, 0, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.2);
                transform: scale(0.94) translateY(12px);
                transition: transform 0.28s cubic-bezier(0.16, 1, 0.3, 1);
                display: flex;
                flex-direction: column;
                gap: 18px;
                max-height: 90vh;
                overflow-y: auto;
                scrollbar-width: thin;
                scrollbar-color: rgba(16, 185, 129, 0.4) transparent;
            }

            .eco-modal-card::-webkit-scrollbar {
                width: 6px;
            }

            .eco-modal-card::-webkit-scrollbar-track {
                background: transparent;
                margin: 14px 0;
            }

            .eco-modal-card::-webkit-scrollbar-thumb {
                background: rgba(16, 185, 129, 0.35);
                border-radius: 8px;
            }

            .eco-modal-backdrop.active .eco-modal-card {
                transform: scale(1) translateY(0);
            }

            .eco-modal-header {
                display: flex;
                align-items: center;
                gap: 14px;
            }

            .eco-modal-icon-badge {
                width: 48px;
                height: 48px;
                min-width: 48px;
                border-radius: 14px;
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 22px;
            }

            .eco-modal-icon-badge.info { background: rgba(59, 130, 246, 0.18); color: #60a5fa; border: 1px solid rgba(59, 130, 246, 0.35); }
            .eco-modal-icon-badge.success { background: rgba(16, 185, 129, 0.18); color: #7dffc4; border: 1px solid rgba(16, 185, 129, 0.35); }
            .eco-modal-icon-badge.warning { background: rgba(245, 158, 11, 0.18); color: #fde68a; border: 1px solid rgba(245, 158, 11, 0.35); }
            .eco-modal-icon-badge.danger { background: rgba(239, 68, 68, 0.18); color: #fca5a5; border: 1px solid rgba(239, 68, 68, 0.35); }

            .eco-modal-title-group h3 {
                margin: 0;
                font-size: 16px;
                font-weight: 700;
                color: #ffffff;
                letter-spacing: -0.01em;
            }

            .eco-modal-title-group p {
                margin: 3px 0 0;
                font-size: 12px;
                color: #94a3b8;
            }

            .eco-modal-message {
                font-size: 13.5px;
                line-height: 1.55;
                color: #e2e8f0;
                white-space: pre-line;
            }

            .eco-modal-input {
                width: 100%;
                box-sizing: border-box;
                padding: 12px 16px;
                border-radius: 12px;
                background: rgba(0, 0, 0, 0.35);
                border: 1px solid rgba(255, 255, 255, 0.15);
                color: #ffffff;
                font-size: 14px;
                font-family: inherit;
                outline: none;
                transition: all 0.2s ease;
            }
            .eco-modal-input:focus {
                border-color: #10b981;
                background: rgba(0, 0, 0, 0.5);
                box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2);
            }

            .eco-modal-actions {
                display: flex;
                align-items: center;
                justify-content: flex-end;
                gap: 10px;
                margin-top: 4px;
            }

            .eco-btn-glass-cancel {
                padding: 10px 18px;
                border-radius: 12px;
                background: rgba(255, 255, 255, 0.08);
                border: 1px solid rgba(255, 255, 255, 0.12);
                color: #cbd5e1;
                font-size: 13px;
                font-weight: 600;
                font-family: inherit;
                cursor: pointer;
                transition: all 0.15s ease;
            }
            .eco-btn-glass-cancel:hover {
                background: rgba(255, 255, 255, 0.14);
                color: #ffffff;
            }

            .eco-btn-glass-confirm {
                padding: 10px 20px;
                border-radius: 12px;
                background: linear-gradient(135deg, #10b981 0%, #059669 100%);
                border: 1px solid rgba(255, 255, 255, 0.2);
                color: #ffffff;
                font-size: 13px;
                font-weight: 700;
                font-family: inherit;
                cursor: pointer;
                box-shadow: 0 4px 14px rgba(16, 185, 129, 0.4);
                transition: all 0.15s ease;
            }
            .eco-btn-glass-confirm:hover {
                transform: translateY(-1px);
                box-shadow: 0 6px 20px rgba(16, 185, 129, 0.55);
            }

            .eco-btn-glass-confirm.danger {
                background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
                box-shadow: 0 4px 14px rgba(239, 68, 68, 0.4);
            }
            .eco-btn-glass-confirm.danger:hover {
                box-shadow: 0 6px 20px rgba(239, 68, 68, 0.55);
            }
        `;
        document.head.appendChild(style);
    }

    // 2. Creación del contenedor de toasts
    function getToastContainer() {
        let container = document.getElementById('eco-toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'eco-toast-container';
            container.className = 'eco-toast-container';
            document.body.appendChild(container);
        }
        return container;
    }

    // 3. Función ecoToast
    window.ecoToast = function(message, type = 'info', duration = 3500) {
        if (!document.body) {
            console.log(message);
            return;
        }
        const container = getToastContainer();

        const toast = document.createElement('div');
        toast.className = `eco-toast eco-toast-${type}`;

        let iconSymbol = 'ℹ️';
        if (type === 'success') iconSymbol = '✓';
        else if (type === 'danger') iconSymbol = '✕';
        else if (type === 'warning') iconSymbol = '⚠️';

        toast.innerHTML = `
            <div class="eco-toast-icon">${iconSymbol}</div>
            <div class="eco-toast-content">${escapeText(message)}</div>
            <button type="button" class="eco-toast-close" title="Cerrar">&times;</button>
        `;

        function dismiss() {
            toast.classList.add('hiding');
            setTimeout(() => {
                if (toast.parentElement) toast.remove();
            }, 250);
        }

        toast.querySelector('.eco-toast-close').addEventListener('click', (e) => {
            e.stopPropagation();
            dismiss();
        });
        toast.addEventListener('click', dismiss);

        container.appendChild(toast);

        if (duration > 0) {
            setTimeout(dismiss, duration);
        }
    };

    // 4. Modal Engine para Alert / Confirm / Prompt
    function createModalBackdrop() {
        let backdrop = document.getElementById('eco-modal-backdrop');
        if (!backdrop) {
            backdrop = document.createElement('div');
            backdrop.id = 'eco-modal-backdrop';
            backdrop.className = 'eco-modal-backdrop';
            document.body.appendChild(backdrop);
        }
        return backdrop;
    }

    // ecoConfirm: Modal emergente Glassmorphism con botones Aceptar / Cancelar
    window.ecoConfirm = function(message, title = 'Confirmación', options = {}) {
        return new Promise((resolve) => {
            const backdrop = createModalBackdrop();
            const isDanger = options.danger || message.toLowerCase().includes('eliminar') || message.toLowerCase().includes('inhabilitar');
            const confirmText = options.confirmText || (isDanger ? 'Eliminar' : 'Aceptar');
            const cancelText = options.cancelText || 'Cancelar';
            const iconBadgeClass = isDanger ? 'danger' : (options.type || 'warning');
            const iconSymbol = isDanger ? '🗑️' : '⚠️';

            backdrop.innerHTML = `
                <div class="eco-modal-card" role="dialog" aria-modal="true">
                    <div class="eco-modal-header">
                        <div class="eco-modal-icon-badge ${iconBadgeClass}">${iconSymbol}</div>
                        <div class="eco-modal-title-group">
                            <h3>${escapeText(title)}</h3>
                            <p>EcoRuta · Sistema de Gestión</p>
                        </div>
                    </div>
                    <div class="eco-modal-message">${escapeText(message)}</div>
                    <div class="eco-modal-actions">
                        <button type="button" class="eco-btn-glass-cancel" id="ecoModalBtnCancel">${escapeText(cancelText)}</button>
                        <button type="button" class="eco-btn-glass-confirm ${isDanger ? 'danger' : ''}" id="ecoModalBtnConfirm">${escapeText(confirmText)}</button>
                    </div>
                </div>
            `;

            backdrop.classList.add('active');

            const btnConfirm = backdrop.querySelector('#ecoModalBtnConfirm');
            const btnCancel = backdrop.querySelector('#ecoModalBtnCancel');

            btnConfirm.focus();

            function cleanup(result) {
                backdrop.classList.remove('active');
                setTimeout(() => { backdrop.innerHTML = ''; }, 260);
                resolve(result);
            }

            btnConfirm.onclick = () => cleanup(true);
            btnCancel.onclick = () => cleanup(false);

            backdrop.onclick = (e) => {
                if (e.target === backdrop) cleanup(false);
            };
        });
    };

    // ecoPrompt: Modal emergente Glassmorphism con campo de entrada
    window.ecoPrompt = function(message, defaultValue = '', title = 'Ingresar Dato') {
        return new Promise((resolve) => {
            const backdrop = createModalBackdrop();

            backdrop.innerHTML = `
                <div class="eco-modal-card" role="dialog" aria-modal="true">
                    <div class="eco-modal-header">
                        <div class="eco-modal-icon-badge info">✏️</div>
                        <div class="eco-modal-title-group">
                            <h3>${escapeText(title)}</h3>
                            <p>EcoRuta · Formulario</p>
                        </div>
                    </div>
                    <div class="eco-modal-message">${escapeText(message)}</div>
                    <input type="text" class="eco-modal-input" id="ecoModalPromptInput" value="${escapeText(defaultValue)}" />
                    <div class="eco-modal-actions">
                        <button type="button" class="eco-btn-glass-cancel" id="ecoModalBtnCancel">Cancelar</button>
                        <button type="button" class="eco-btn-glass-confirm" id="ecoModalBtnConfirm">Guardar</button>
                    </div>
                </div>
            `;

            backdrop.classList.add('active');

            const input = backdrop.querySelector('#ecoModalPromptInput');
            const btnConfirm = backdrop.querySelector('#ecoModalBtnConfirm');
            const btnCancel = backdrop.querySelector('#ecoModalBtnCancel');

            input.focus();
            input.select();

            function cleanup(result) {
                backdrop.classList.remove('active');
                setTimeout(() => { backdrop.innerHTML = ''; }, 260);
                resolve(result);
            }

            btnConfirm.onclick = () => cleanup(input.value.trim());
            btnCancel.onclick = () => cleanup(null);

            input.onkeydown = (e) => {
                if (e.key === 'Enter') cleanup(input.value.trim());
                if (e.key === 'Escape') cleanup(null);
            };

            backdrop.onclick = (e) => {
                if (e.target === backdrop) cleanup(null);
            };
        });
    };

    // ecoAlert: Modal o Toast inteligente según longitud del mensaje
    window.ecoAlert = function(message, title = 'Notificación') {
        return new Promise((resolve) => {
            const str = String(message || '');
            const isSuccess = str.includes('✅') || str.toLowerCase().includes('éxito') || str.toLowerCase().includes('exitosamente') || str.toLowerCase().includes('copiado');
            const isDanger = str.includes('❌') || str.toLowerCase().includes('error') || str.toLowerCase().includes('expirada') || str.toLowerCase().includes('denegado') || str.toLowerCase().includes('restringido');
            const isWarning = str.includes('⚠️') || str.toLowerCase().includes('advertencia') || str.toLowerCase().includes('suficientes');

            let type = 'info';
            if (isSuccess) type = 'success';
            else if (isDanger) type = 'danger';
            else if (isWarning) type = 'warning';

            // Si es un mensaje corto (como "Ticket copiado" o "Error al guardar"), mostrar Toast elegante
            if (str.length < 90 && !str.includes('\n')) {
                window.ecoToast(str, type, 4000);
                resolve();
                return;
            }

            // Si es un mensaje largo o multilínea, mostrar Modal Glassmorphism
            const backdrop = createModalBackdrop();
            let iconSymbol = isSuccess ? '✅' : (isDanger ? '❌' : (isWarning ? '⚠️' : 'ℹ️'));

            backdrop.innerHTML = `
                <div class="eco-modal-card" role="dialog" aria-modal="true">
                    <div class="eco-modal-header">
                        <div class="eco-modal-icon-badge ${type}">${iconSymbol}</div>
                        <div class="eco-modal-title-group">
                            <h3>${escapeText(title)}</h3>
                            <p>EcoRuta · Sistema de Gestión</p>
                        </div>
                    </div>
                    <div class="eco-modal-message">${escapeText(str)}</div>
                    <div class="eco-modal-actions">
                        <button type="button" class="eco-btn-glass-confirm" id="ecoModalBtnOk">Entendido</button>
                    </div>
                </div>
            `;

            backdrop.classList.add('active');
            const btnOk = backdrop.querySelector('#ecoModalBtnOk');
            btnOk.focus();

            function cleanup() {
                backdrop.classList.remove('active');
                setTimeout(() => { backdrop.innerHTML = ''; }, 260);
                resolve();
            }

            btnOk.onclick = cleanup;
            backdrop.onclick = (e) => { if (e.target === backdrop) cleanup(); };
        });
    };

    // 5. Interceptores globales para reemplazar automáticamente alert()
    window.alert = function(msg) {
        window.ecoAlert(msg);
    };

    function escapeText(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
})();
