(function() {
    // ====== الإعدادات (الأداة تعدلها أثناء البناء) ======
    const SPLASH_CONFIG = {
        appName: "Made by Dark",
        footer: "",
        duration: 2000,
        bgColor: "#0f0f1a",
        textColor: "#a29bfe",
        logoSrc: "splash-logo.png"
    };

    // ====== حقن CSS ======
    const style = document.createElement('style');
    style.textContent = `
        #dark-splash {
            position: fixed;
            inset: 0;
            background: ${SPLASH_CONFIG.bgColor};
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 999999;
            transition: opacity 0.7s ease, visibility 0.7s ease;
            font-family: system-ui, -apple-system, sans-serif;
        }
        #dark-splash.hidden { opacity: 0; visibility: hidden; pointer-events: none; }
        .dark-splash-content {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            gap: 20px;
            padding: 20px;
            text-align: center;
        }
        #dark-splash-logo {
            width: 120px;
            height: 120px;
            object-fit: contain;
            animation: darkSplashLogo 1.4s ease-out;
            filter: drop-shadow(0 0 20px rgba(108, 92, 231, 0.5));
        }
        .dark-splash-title {
            font-size: 1.8rem;
            font-weight: bold;
            color: ${SPLASH_CONFIG.textColor};
            letter-spacing: 2px;
            animation: darkSplashFade 1.2s ease-out 0.3s both;
        }
        .dark-splash-footer {
            position: absolute;
            bottom: 30px;
            font-size: 0.85rem;
            color: ${SPLASH_CONFIG.textColor};
            opacity: 0.5;
            animation: darkSplashFade 1.2s ease-out 0.8s both;
        }
        .dark-splash-loader {
            display: flex;
            gap: 8px;
            margin-top: 10px;
            animation: darkSplashFade 1s ease-out 0.6s both;
        }
        .dark-splash-loader span {
            width: 10px;
            height: 10px;
            border-radius: 50%;
            background: ${SPLASH_CONFIG.textColor};
            animation: darkSplashDot 1.4s ease-in-out infinite;
        }
        .dark-splash-loader span:nth-child(2) { animation-delay: 0.2s; }
        .dark-splash-loader span:nth-child(3) { animation-delay: 0.4s; }
        @keyframes darkSplashLogo {
            0% { transform: scale(0.5) rotate(-10deg); opacity: 0; }
            60% { transform: scale(1.1) rotate(5deg); opacity: 1; }
            100% { transform: scale(1) rotate(0); opacity: 1; }
        }
        @keyframes darkSplashFade {
            from { opacity: 0; transform: translateY(10px); }
            to { opacity: 1; transform: translateY(0); }
        }
        @keyframes darkSplashDot {
            0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
            40% { transform: scale(1); opacity: 1; }
        }
    `;
    document.head.appendChild(style);

    // ====== إنشاء HTML ======
    const splash = document.createElement('div');
    splash.id = 'dark-splash';
    splash.innerHTML = `
        <div class="dark-splash-content">
            <img id="dark-splash-logo" src="${SPLASH_CONFIG.logoSrc}" alt="logo" onerror="this.style.display='none'">
            <div class="dark-splash-title">${SPLASH_CONFIG.appName}</div>
            <div class="dark-splash-loader">
                <span></span><span></span><span></span>
            </div>
            ${SPLASH_CONFIG.footer ? `<div class="dark-splash-footer">${SPLASH_CONFIG.footer}</div>` : ''}
        </div>
    `;

    // ====== إضافة للصفحة ======
    function showSplash() {
        document.body.insertAdjacentHTML('afterbegin', splash.outerHTML);
        setTimeout(() => {
            const el = document.getElementById('dark-splash');
            if (el) {
                el.classList.add('hidden');
                setTimeout(() => el.remove(), 800);
            }
        }, SPLASH_CONFIG.duration);
    }

    // ====== التشغيل ======
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', showSplash);
    } else {
        showSplash();
    }
})();