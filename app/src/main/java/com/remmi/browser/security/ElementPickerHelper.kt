package com.remmi.browser.security

import org.json.JSONObject

object ElementPickerHelper {

  fun getPickerInjectionScript(): String {
    val d = "$"
    return """
      (function() {
        if (window.__remmiPickerActive) {
          if (window.__remmiPickerCleanup) window.__remmiPickerCleanup();
          return;
        }
        window.__remmiPickerActive = true;

        var existingStyle = document.getElementById('remmi-picker-styles');
        if (existingStyle) existingStyle.remove();

        var styleEl = document.createElement('style');
        styleEl.id = 'remmi-picker-styles';
        styleEl.textContent = '#remmi-picker-highlight { position: absolute !important; pointer-events: none !important; z-index: 2147483640 !important; border: 2.5px solid #ef4444 !important; background: rgba(239, 68, 68, 0.25) !important; border-radius: 6px !important; box-shadow: 0 0 16px rgba(239, 68, 68, 0.6) !important; transition: all 0.05s ease-out !important; display: none; box-sizing: border-box !important; }' +
          '#remmi-picker-badge { position: absolute !important; bottom: calc(100% + 6px) !important; left: 0 !important; background: #18181b !important; color: #ffffff !important; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif !important; font-size: 11px !important; font-weight: 700 !important; padding: 3px 8px !important; border-radius: 4px !important; white-space: nowrap !important; pointer-events: none !important; border: 1px solid #ef4444 !important; box-shadow: 0 2px 10px rgba(0,0,0,0.7) !important; }' +
          '#remmi-picker-card { position: fixed !important; bottom: 24px !important; left: 50% !important; transform: translateX(-50%) !important; background: #18181b !important; color: #f4f4f5 !important; border: 1.5px solid #3f3f46 !important; border-radius: 18px !important; padding: 14px 16px !important; z-index: 2147483647 !important; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif !important; box-shadow: 0 12px 32px rgba(0,0,0,0.8) !important; width: calc(100% - 32px) !important; max-width: 440px !important; display: none; flex-direction: column !important; gap: 10px !important; box-sizing: border-box !important; }' +
          '#remmi-picker-card * { box-sizing: border-box !important; }' +
          '#remmi-picker-card .picker-header { display: flex !important; justify-content: space-between !important; align-items: center !important; }' +
          '#remmi-picker-card .picker-title { font-size: 14px !important; font-weight: 800 !important; color: #ef4444 !important; display: flex !important; align-items: center !important; gap: 6px !important; }' +
          '#remmi-picker-card .picker-selector { font-family: monospace !important; font-size: 12px !important; background: #27272a !important; color: #d4d4d8 !important; padding: 8px 10px !important; border-radius: 8px !important; word-break: break-all !important; border: 1px solid #3f3f46 !important; max-height: 55px !important; overflow-y: auto !important; }' +
          '#remmi-picker-card .picker-actions { display: flex !important; gap: 8px !important; }' +
          '#remmi-picker-card button { min-height: 40px !important; border: none !important; border-radius: 10px !important; font-size: 12.5px !important; font-weight: 700 !important; cursor: pointer !important; padding: 8px 12px !important; touch-action: manipulation !important; }' +
          '#remmi-picker-card .btn-block { flex: 1.4 !important; background: #ef4444 !important; color: #ffffff !important; }' +
          '#remmi-picker-card .btn-preview { flex: 1 !important; background: #3f3f46 !important; color: #fafafa !important; }' +
          '#remmi-picker-card .btn-parent { flex: 0 0 auto !important; background: #27272a !important; color: #e4e4e7 !important; border: 1px solid #52525b !important; padding: 8px 10px !important; }' +
          '#remmi-picker-card .btn-close { background: transparent !important; color: #a1a1aa !important; font-size: 16px !important; border: none !important; padding: 4px 8px !important; cursor: pointer !important; }';
        (document.head || document.documentElement).appendChild(styleEl);

        var existingHighlight = document.getElementById('remmi-picker-highlight');
        if (existingHighlight) existingHighlight.remove();

        var highlightBox = document.createElement('div');
        highlightBox.id = 'remmi-picker-highlight';
        var badge = document.createElement('div');
        badge.id = 'remmi-picker-badge';
        highlightBox.appendChild(badge);
        (document.body || document.documentElement).appendChild(highlightBox);

        var existingCard = document.getElementById('remmi-picker-card');
        if (existingCard) existingCard.remove();

        var card = document.createElement('div');
        card.id = 'remmi-picker-card';
        card.innerHTML = '<div class="picker-header"><div class="picker-title">🛡️ Block Element</div><button class="btn-close" id="remmi-picker-close">✕</button></div><div class="picker-selector" id="remmi-picker-selector-text">Target element</div><div class="picker-actions"><button class="btn-parent" id="remmi-picker-parent" title="Select parent element">▲ Parent</button><button class="btn-preview" id="remmi-picker-preview">👁️ Preview</button><button class="btn-block" id="remmi-picker-confirm">Block Element</button></div>';
        (document.body || document.documentElement).appendChild(card);

        var currentTarget = null;
        var isPreviewHidden = false;
        var currentSelector = '';

        function computeCssSelector(el) {
          if (!el || el === document.body || el === document.documentElement) return 'body';
          if (el.id && !/^\d|[^\w-]/.test(el.id)) {
            return '#' + CSS.escape(el.id);
          }
          var tagName = el.tagName.toLowerCase();
          if (el.classList && el.classList.length > 0) {
            var validClasses = Array.from(el.classList).filter(function(c) {
              return !c.startsWith('remmi-') && !/^\d/.test(c) && c.length < 50;
            });
            if (validClasses.length > 0) {
              return tagName + '.' + validClasses.slice(0, 3).map(function(c) { return CSS.escape(c); }).join('.');
            }
          }
          if (el.getAttribute('data-ad-slot')) return '[data-ad-slot="' + el.getAttribute('data-ad-slot') + '"]';
          if (el.getAttribute('role')) return tagName + '[role="' + el.getAttribute('role') + '"]';
          
          var parent = el.parentElement;
          if (parent && parent !== document.body && parent !== document.documentElement) {
            var idx = Array.from(parent.children).indexOf(el) + 1;
            return computeCssSelector(parent) + ' > ' + tagName + ':nth-child(' + idx + ')';
          }
          return tagName;
        }

        function updateHighlight(el) {
          if (!el || el === highlightBox || el === card || card.contains(el) || el === document.body || el === document.documentElement) {
            highlightBox.style.display = 'none';
            return;
          }
          var rect = el.getBoundingClientRect();
          if (rect.width === 0 || rect.height === 0) return;

          var scrollX = window.scrollX || window.pageXOffset || 0;
          var scrollY = window.scrollY || window.pageYOffset || 0;

          highlightBox.style.left = (rect.left + scrollX) + 'px';
          highlightBox.style.top = (rect.top + scrollY) + 'px';
          highlightBox.style.width = rect.width + 'px';
          highlightBox.style.height = rect.height + 'px';
          highlightBox.style.display = 'block';

          var tag = el.tagName.toLowerCase();
          var id = el.id ? '#' + el.id : '';
          var cls = (el.className && typeof el.className === 'string') ? '.' + el.className.split(' ').filter(Boolean).slice(0, 2).join('.') : '';
          badge.textContent = tag + id + cls + ' (' + Math.round(rect.width) + '×' + Math.round(rect.height) + ')';
        }

        function onPointerMove(e) {
          if (card.style.display === 'flex' && isPreviewHidden) return;
          var el = document.elementFromPoint(e.clientX, e.clientY);
          if (el && !card.contains(el) && el !== highlightBox && el !== badge) {
            updateHighlight(el);
          }
        }

        function onPointerClick(e) {
          var el = document.elementFromPoint(e.clientX, e.clientY);
          if (!el || card.contains(el) || el === highlightBox || el === badge) return;

          e.preventDefault();
          e.stopPropagation();

          currentTarget = el;
          currentSelector = computeCssSelector(el);
          document.getElementById('remmi-picker-selector-text').textContent = currentSelector;
          card.style.display = 'flex';
          highlightBox.style.display = 'block';
          updateHighlight(currentTarget);
        }

        document.getElementById('remmi-picker-parent').onclick = function(e) {
          e.stopPropagation();
          if (currentTarget && currentTarget.parentElement && currentTarget.parentElement !== document.body && currentTarget.parentElement !== document.documentElement) {
            if (isPreviewHidden) {
              currentTarget.style.display = '';
              isPreviewHidden = false;
            }
            currentTarget = currentTarget.parentElement;
            currentSelector = computeCssSelector(currentTarget);
            document.getElementById('remmi-picker-selector-text').textContent = currentSelector;
            updateHighlight(currentTarget);
          }
        };

        document.getElementById('remmi-picker-preview').onclick = function(e) {
          e.stopPropagation();
          if (!currentTarget) return;
          if (isPreviewHidden) {
            currentTarget.style.display = '';
            highlightBox.style.display = 'block';
            this.textContent = '👁️ Preview';
            this.style.background = '#3f3f46';
            this.style.color = '#fafafa';
            isPreviewHidden = false;
          } else {
            currentTarget.style.display = 'none';
            highlightBox.style.display = 'none';
            this.textContent = '↩ Undo';
            this.style.background = '#eab308';
            this.style.color = '#000000';
            isPreviewHidden = true;
          }
        };

        document.getElementById('remmi-picker-confirm').onclick = function(e) {
          e.stopPropagation();
          if (!currentSelector) return;

          try {
            document.querySelectorAll(currentSelector).forEach(function(el) {
              el.style.setProperty('display', 'none', 'important');
            });
            var s = document.createElement('style');
            s.setAttribute('data-remmi-custom-block', '1');
            s.textContent = currentSelector + ' { display: none !important; }';
            (document.head || document.documentElement).appendChild(s);
          } catch (_e) {}

          // Notify extension or native bridge
          try {
            if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.sendMessage) {
              browser.runtime.sendMessage({
                type: 'BLOCK_ELEMENT',
                selector: currentSelector,
                domain: window.location.hostname
              });
            }
          } catch (_e) {}

          try {
            window.postMessage({
              type: 'REMMI_BLOCK_ELEMENT_CONFIRMED',
              selector: currentSelector,
              host: window.location.hostname
            }, '*');
          } catch (_e) {}

          cleanup();
        };

        document.getElementById('remmi-picker-close').onclick = function(e) {
          e.stopPropagation();
          cleanup();
        };

        function cleanup() {
          if (isPreviewHidden && currentTarget) {
            currentTarget.style.display = '';
          }
          window.removeEventListener('mousemove', onPointerMove, true);
          window.removeEventListener('click', onPointerClick, true);
          window.removeEventListener('touchend', onPointerClick, true);
          if (styleEl) styleEl.remove();
          if (highlightBox) highlightBox.remove();
          if (card) card.remove();
          window.__remmiPickerActive = false;
          window.__remmiPickerCleanup = null;
        }

        window.__remmiPickerCleanup = cleanup;
        window.addEventListener('mousemove', onPointerMove, true);
        window.addEventListener('click', onPointerClick, true);
        window.addEventListener('touchend', onPointerClick, { capture: true, passive: false });
      })();
    """.trimIndent()
  }

  fun getRemovePickerScript(): String {
    return """
      (function() {
        if (window.__remmiPickerCleanup) {
          window.__remmiPickerCleanup();
        }
      })();
    """.trimIndent()
  }

  fun getApplyCustomSelectorScript(selector: String): String {
    val escaped = JSONObject.quote(selector)
    return """
      (function() {
        try {
          var sel = $escaped;
          document.querySelectorAll(sel).forEach(function(el) {
            el.style.setProperty('display', 'none', 'important');
          });
          var s = document.createElement('style');
          s.setAttribute('data-remmi-custom-block', '1');
          s.textContent = sel + ' { display: none !important; }';
          (document.head || document.documentElement).appendChild(s);
        } catch (_e) {}
      })();
    """.trimIndent()
  }
}
