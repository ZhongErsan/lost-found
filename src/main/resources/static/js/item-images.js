(() => {
    'use strict';

    const auth = window.LostFoundAuth;
    const previews = new Map();

    document.querySelectorAll('.photo-input').forEach(input => {
        const type = input.id === 'found-photo' ? 'found' : 'lost';
        const preview = document.querySelector(`[data-photo-preview="${type}"]`);
        const image = preview.querySelector('img');
        const caption = preview.querySelector('figcaption');
        const feedback = document.querySelector(`[data-photo-feedback="${type}"]`);
        let objectUrl = null;

        function clear(resetInput = true) {
            if (objectUrl) URL.revokeObjectURL(objectUrl);
            objectUrl = null;
            image.removeAttribute('src');
            preview.hidden = true;
            caption.textContent = '';
            if (resetInput) input.value = '';
            previews.delete(type);
        }

        function showError(message) {
            clear();
            feedback.textContent = message;
            feedback.hidden = false;
        }

        input.addEventListener('change', () => {
            const file = input.files[0];
            clear(false);
            feedback.hidden = true;
            feedback.textContent = '';
            if (!file) return;
            if (!['image/jpeg', 'image/png'].includes(file.type)) {
                showError('请选择 JPG 或 PNG 格式的照片。');
                return;
            }
            if (!file.size || file.size > 5 * 1024 * 1024) {
                showError(file.size ? '照片不能超过 5MB。' : '所选照片为空，请重新选择。');
                return;
            }
            objectUrl = URL.createObjectURL(file);
            const selectedUrl = objectUrl;
            image.onload = () => {
                if (objectUrl !== selectedUrl) return;
                if (image.naturalWidth > 8000 || image.naturalHeight > 8000
                        || image.naturalWidth * image.naturalHeight > 16_000_000) {
                    showError('照片尺寸过大，请缩小后重新选择。');
                    return;
                }
                preview.hidden = false;
                caption.textContent = `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB`;
                previews.set(type, file);
            };
            image.onerror = () => {
                if (objectUrl !== selectedUrl) return;
                showError('照片无法预览，请选择一张完整的 JPG 或 PNG 图片。');
            };
            image.src = selectedUrl;
        });

        preview.querySelector('[data-photo-remove]').addEventListener('click', () => {
            clear();
            feedback.hidden = true;
            feedback.textContent = '';
        });
        input.closest('dialog').addEventListener('close', () => {
            clear();
            feedback.hidden = true;
            feedback.textContent = '';
        });
    });

    function imageUrl(value) {
        if (typeof value !== 'string' || !/^\/uploads\/[a-zA-Z0-9-]+\.(?:jpe?g|png)$/.test(value)) return null;
        return auth.url(value.slice(1)).href;
    }

    function appendImage(parent, value, description) {
        const url = imageUrl(value);
        if (!url) return;
        const figure = document.createElement('figure');
        figure.className = 'item-photo';
        const link = document.createElement('a');
        link.href = url;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.setAttribute('aria-label', description + '，打开原图');
        const img = document.createElement('img');
        img.src = url;
        img.alt = description;
        img.loading = 'lazy';
        const imageCaption = document.createElement('figcaption');
        imageCaption.textContent = description + ' · 点击查看原图';
        img.addEventListener('error', () => {
            link.remove();
            imageCaption.textContent = description + '暂时无法加载';
        }, { once: true });
        link.append(img);
        figure.append(link, imageCaption);
        parent.append(figure);
    }

    window.LostFoundImages = Object.freeze({ appendImage, imageUrl, selectedPhoto: type => previews.get(type) || null });
})();
