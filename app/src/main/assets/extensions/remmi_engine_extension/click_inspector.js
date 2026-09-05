// Remmi Click Transparency & DOM Inspection Content Script
// Inspects real click targets, detects deceptive overlays and extracts navigation candidates.
// NEVER captures passwords, form values, cookies, or full DOM.
(function() {
  'use strict';

  let lastHandledGestureTime = 0;
  let lastHandledCoords = { x: -1, y: -1 };
  let lastHandledTarget = null;

  function getRegistrableDomain(urlStr, baseUrl) {
    try {
      const parsed = new URL(urlStr, baseUrl || window.location.href);
      const host = parsed.hostname.toLowerCase();
      if (/^[\d.]+$|^\[.*\]$/.test(host)) return host;
      const parts = host.split('.');
      if (parts.length <= 2) return host;
      const tld2 = parts.slice(-2).join('.');
      const multiPartTlds = ['co.uk', 'com.au', 'co.nz', 'org.uk', 'co.jp', 'com.br', 'gov.uk', 'edu.au'];
      if (multiPartTlds.includes(tld2) && parts.length > 2) {
        return parts.slice(-3).join('.');
      }
      return parts.slice(-2).join('.');
    } catch (_e) {
      return urlStr;
    }
  }

  function checkOverlayProperties(el) {
    if (!el || el === document.body || el === document.documentElement) {
      return { isOverlay: false, isTransparent: false, details: '' };
    }
    try {
      const style = window.getComputedStyle(el);
      const opacity = parseFloat(style.opacity);
      const isLowOpacity = !isNaN(opacity) && opacity < 0.2;
      const isTransparentBg = style.backgroundColor === 'transparent' || style.backgroundColor === 'rgba(0, 0, 0, 0)';
      const isFixedOrAbs = style.position === 'fixed' || style.position === 'absolute';
      const zIndex = parseInt(style.zIndex, 10);
      const isHighZ = !isNaN(zIndex) && zIndex >= 100;
      
      const rect = el.getBoundingClientRect();
      const isLargeArea = rect.width >= (window.innerWidth * 0.4) && rect.height >= (window.innerHeight * 0.4);
      
      // Combined evidence heuristic:
      // Do not classify every opacity < 0.2 + fixed/abs as malicious on its own.
      // Flag as SUSPICIOUS_OVERLAY ONLY when transparency/low-opacity combines with covering high z-index,
      // screen-covering dimensions, or positioning over interactive coordinates.
      const isSuspiciousOverlay = (isLowOpacity && isFixedOrAbs) ||
                                  (isTransparentBg && isFixedOrAbs && isHighZ) ||
                                  (isTransparentBg && isLargeArea && isFixedOrAbs) ||
                                  (isLowOpacity && isLargeArea);

      const detailsList = [];
      if (opacity < 1) detailsList.push('opacity: ' + opacity);
      if (isFixedOrAbs) detailsList.push('position: ' + style.position);
      if (!isNaN(zIndex) && zIndex !== 0) detailsList.push('z-index: ' + zIndex);
      if (isLargeArea) detailsList.push('screen-cover');

      return {
        isOverlay: isSuspiciousOverlay,
        isTransparent: isLowOpacity || isTransparentBg,
        details: detailsList.join(', ')
      };
    } catch (_e) {
      return { isOverlay: false, isTransparent: false, details: '' };
    }
  }

  function parseJsNavigation(code) {
    if (!code || typeof code !== 'string') return [];
    const results = [];
    const patterns = [
      /(?:location\.href|location\.assign|location\.replace|window\.location(?:\.href)?)\s*(?:=|(?:\())\s*["']([^"']+)["']/g,
      /window\.open\s*\(\s*["']([^"']+)["']/g,
      /(?:location|window\["location"\])\["href"\]\s*=\s*["']([^"']+)["']/g,
      /window\["location"\]\["(?:replace|assign)"\]\s*\(\s*["']([^"']+)["']/g
    ];

    for (let p = 0; p < patterns.length; p++) {
      let match;
      while ((match = patterns[p].exec(code)) !== null) {
        if (match[1] && (match[1].startsWith('http') || match[1].startsWith('/'))) {
          results.push(match[1]);
        }
      }
    }
    return results;
  }

  function extractUrlsFromElement(el) {
    if (!el) return [];
    const found = [];

    // 1. Direct href
    if (el.href && typeof el.href === 'string' && el.href.trim() && !el.href.startsWith('javascript:void') && el.href !== '#') {
      found.push({ url: el.href.trim(), source: 'href', isPopup: el.target === '_blank' });
    }

    // 2. data-href / data-url / data-target / data-link
    const dataHref = el.getAttribute('data-href') || el.getAttribute('data-url') || el.getAttribute('data-target') || el.getAttribute('data-link');
    if (dataHref && dataHref.trim() && (dataHref.startsWith('http') || dataHref.startsWith('/'))) {
      found.push({ url: dataHref.trim(), source: 'data-attribute', isPopup: false });
    }

    // 3. onclick attribute parsing
    const onclickStr = el.getAttribute('onclick') || '';
    if (onclickStr) {
      const extracted = parseJsNavigation(onclickStr);
      for (let i = 0; i < extracted.length; i++) {
        found.push({ url: extracted[i], source: 'onclick', isPopup: onclickStr.includes('window.open') });
      }
    }

    // 4. form action
    if (el.tagName && el.tagName.toLowerCase() === 'form' && el.action) {
      found.push({ url: el.action, source: 'form-action', isPopup: el.target === '_blank' });
    }

    return found;
  }

  function isNormalUnambiguousLink(directTarget, x, y) {
    if (!directTarget) return false;
    // Fast path check: if element or its parent is an anchor with normal style and no overlay indicators
    const anchor = directTarget.tagName && directTarget.tagName.toLowerCase() === 'a' ? directTarget : (directTarget.closest ? directTarget.closest('a') : null);
    if (!anchor || !anchor.href) return false;

    // Check if target or anchor has low opacity or fixed/absolute overlay properties
    const directOverlay = checkOverlayProperties(directTarget);
    if (directOverlay.isOverlay || (directOverlay.isTransparent && directOverlay.details.includes('position'))) return false;

    const anchorOverlay = checkOverlayProperties(anchor);
    if (anchorOverlay.isOverlay || (anchorOverlay.isTransparent && anchorOverlay.details.includes('position'))) return false;

    // Check for suspicious onclick popup
    const onclick = anchor.getAttribute('onclick') || directTarget.getAttribute('onclick') || '';
    if (onclick.includes('window.open') || onclick.includes('location.')) return false;

    // Check for domain display mismatch
    const visibleText = (directTarget.innerText || anchor.innerText || '').trim();
    if (visibleText.startsWith('http://') || visibleText.startsWith('https://')) {
      try {
        const visibleHost = new URL(visibleText).hostname.toLowerCase();
        const actualHost = new URL(anchor.href, window.location.href).hostname.toLowerCase();
        if (visibleHost && actualHost && visibleHost !== actualHost) return false;
      } catch (_e) {}
    }

    // Fast-path security fix: Lightweight top-layer sanity check at coordinates (x, y)
    // Normal links remain fast, but if another clickable/transparent/overlay element is covering coordinates, DO NOT use fast path.
    if (x != null && y != null && document.elementFromPoint) {
      try {
        const topEl = document.elementFromPoint(x, y);
        if (topEl && topEl !== directTarget && topEl !== anchor) {
          const isDescendant = anchor.contains(topEl) || directTarget.contains(topEl);
          const isAncestor = topEl.contains(anchor) || topEl.contains(directTarget);
          if (!isDescendant && !isAncestor) {
            // An unrelated element is sitting above the link at these coordinates
            return false;
          }
          const topOverlay = checkOverlayProperties(topEl);
          if (topOverlay.isOverlay || (topOverlay.isTransparent && topOverlay.details.includes('position'))) {
            return false;
          }
        }

        if (document.elementsFromPoint) {
          const topElements = document.elementsFromPoint(x, y);
          for (let i = 0; i < Math.min(topElements.length, 3); i++) {
            const el = topElements[i];
            if (el === directTarget || el === anchor || anchor.contains(el)) continue;
            const overlay = checkOverlayProperties(el);
            if (overlay.isOverlay || (overlay.isTransparent && overlay.details.includes('position'))) {
              return false;
            }
            const elUrls = extractUrlsFromElement(el);
            if (elUrls.length > 0) {
              return false;
            }
          }
        }
      } catch (_e) {
        return false;
      }
    }

    // Unambiguous normal link -> fast return true
    return true;
  }

  function inspectClickCoordinates(x, y, directTarget) {
    const candidates = [];
    const seenUrls = new Set();

    // 1. Inspect direct target and its semantic parent
    if (directTarget) {
      const directOverlay = checkOverlayProperties(directTarget);
      const directUrls = extractUrlsFromElement(directTarget);
      for (let i = 0; i < directUrls.length; i++) {
        const u = directUrls[i].url;
        if (!seenUrls.has(u)) {
          seenUrls.add(u);
          candidates.push({
            label: directTarget.tagName ? (directTarget.tagName.toUpperCase() + ' (' + directUrls[i].source + ')') : 'Direct Target',
            url: u,
            tagName: (directTarget.tagName || 'div').toLowerCase(),
            type: directOverlay.isOverlay ? 'SUSPICIOUS_OVERLAY' : 'VISIBLE_ELEMENT',
            isOverlay: directOverlay.isOverlay,
            isTransparent: directOverlay.isTransparent,
            isPopup: !!directUrls[i].isPopup,
            confidence: 'HIGH',
            details: directOverlay.details || 'Clicked element'
          });
        }
      }

      // Check closest anchor / clickable ancestor
      const parentAnchor = directTarget.closest ? directTarget.closest('a') : null;
      if (parentAnchor && parentAnchor !== directTarget) {
        const parentOverlay = checkOverlayProperties(parentAnchor);
        const parentUrls = extractUrlsFromElement(parentAnchor);
        for (let i = 0; i < parentUrls.length; i++) {
          const u = parentUrls[i].url;
          if (!seenUrls.has(u)) {
            seenUrls.add(u);
            candidates.push({
              label: 'Parent Anchor <a>',
              url: u,
              tagName: 'a',
              type: parentOverlay.isOverlay ? 'SUSPICIOUS_OVERLAY' : 'PARENT_ANCHOR',
              isOverlay: parentOverlay.isOverlay,
              isTransparent: parentOverlay.isTransparent,
              isPopup: !!parentUrls[i].isPopup,
              confidence: 'HIGH',
              details: parentOverlay.details || 'Enclosing <a> tag'
            });
          }
        }
      }

      // Check closest form
      const parentForm = directTarget.closest ? directTarget.closest('form') : null;
      if (parentForm && parentForm.action && !seenUrls.has(parentForm.action)) {
        seenUrls.add(parentForm.action);
        candidates.push({
          label: 'Parent Form Action',
          url: parentForm.action,
          tagName: 'form',
          type: 'FORM_ACTION',
          isOverlay: false,
          isTransparent: false,
          isPopup: parentForm.target === '_blank',
          confidence: 'MEDIUM',
          details: 'Form submission endpoint'
        });
      }
    }

    // 2. Inspect all elements under the click point to catch deceptive overlays sitting above/below
    if (document.elementsFromPoint && x != null && y != null) {
      try {
        const elementsUnderPoint = document.elementsFromPoint(x, y);
        for (let idx = 0; idx < Math.min(elementsUnderPoint.length, 10); idx++) {
          const el = elementsUnderPoint[idx];
          if (el === directTarget) continue;

          const overlay = checkOverlayProperties(el);
          const urls = extractUrlsFromElement(el);

          // Only add candidate if navigation target actually exists on this layer
          for (let uIdx = 0; uIdx < urls.length; uIdx++) {
            const u = urls[uIdx].url;
            if (!seenUrls.has(u)) {
              seenUrls.add(u);
              candidates.push({
                label: overlay.isOverlay ? 'Suspicious Overlay Layer' : (el.tagName.toUpperCase() + ' under point'),
                url: u,
                tagName: (el.tagName || 'div').toLowerCase(),
                type: overlay.isOverlay ? 'SUSPICIOUS_OVERLAY' : 'VISIBLE_ELEMENT',
                isOverlay: overlay.isOverlay,
                isTransparent: overlay.isTransparent,
                isPopup: !!urls[uIdx].isPopup,
                confidence: overlay.isOverlay ? 'HIGH' : 'MEDIUM',
                details: overlay.details || 'Layer under click coordinate'
              });
            }
          }
        }
      } catch (_e) {}
    }

    return candidates;
  }

  // Interception decision logic with combined evidence requirement (anti-false-positive)
  function shouldInterceptClick(candidates, directTarget) {
    if (!candidates || candidates.length === 0) return false;

    // 1. Combined evidence overlay heuristic:
    // Suspicious overlay candidate exists with navigation target
    const overlayCandidate = candidates.find(function(c) { return c.isOverlay; });
    if (overlayCandidate) {
      const visibleCandidate = candidates.find(function(c) { return !c.isOverlay; });
      if (visibleCandidate) {
        const regOverlay = getRegistrableDomain(overlayCandidate.url);
        const regVisible = getRegistrableDomain(visibleCandidate.url);
        // Intercept if overlay has different destination URL or different registrable domain
        if (overlayCandidate.url !== visibleCandidate.url || regOverlay !== regVisible) {
          return true;
        }
      }
      // If no separate visible candidate or if overlay has popup / display mismatch deception signals
      if (overlayCandidate.isPopup) {
        return true;
      }
      // Check if visible display text mismatches the overlay URL
      if (directTarget) {
        const visibleText = (directTarget.innerText || '').trim();
        if (visibleText.startsWith('http://') || visibleText.startsWith('https://')) {
          try {
            const textDomain = getRegistrableDomain(visibleText);
            const overlayDomain = getRegistrableDomain(overlayCandidate.url);
            if (textDomain && overlayDomain && textDomain !== overlayDomain) {
              return true;
            }
          } catch (_e) {}
        }
      }
      // If overlay points to the exact same URL/domain as visible target and no other deception signal exists, do NOT intercept
    }

    // 2. Suspicious popup / window.open attached to overlay or transparent element
    const hasSuspiciousPopup = candidates.some(function(c) {
      return c.isPopup && (c.type === 'SUSPICIOUS_OVERLAY' || c.isTransparent);
    });
    if (hasSuspiciousPopup) return true;

    // 3. Mismatch between visible display text/domain and actual destination
    if (directTarget) {
      const visibleText = (directTarget.innerText || '').trim();
      if (visibleText.startsWith('http://') || visibleText.startsWith('https://')) {
        try {
          const textDomain = getRegistrableDomain(visibleText);
          const targetDomain = getRegistrableDomain(candidates[0].url);
          if (textDomain && targetDomain && textDomain !== targetDomain) {
            return true;
          }
        } catch (_e) {}
      }
    }

    // 4. Multi-target heuristic:
    // Do NOT intercept merely because hostname count > 1.
    // Same-site nested links (e.g. news.ycombinator.com and ycombinator.com) remain normal.
    const distinctUrls = Array.from(new Set(candidates.map(function(c) { return c.url; })));
    if (distinctUrls.length > 1) {
      const registrableDomains = new Set(distinctUrls.map(function(u) { return getRegistrableDomain(u); }));
      
      // If different registrable domains AND conflicting layer types / transparency
      if (registrableDomains.size > 1) {
        const hasConflictingLayers = candidates.some(function(c) {
          return c.isTransparent || c.type === 'SUSPICIOUS_OVERLAY' || c.tagName !== candidates[0].tagName;
        });
        if (hasConflictingLayers) {
          return true;
        }
      }
    }

    // Standard legitimate nested HTML -> do NOT block
    return false;
  }

  function handleInteractionEvent(e) {
    try {
      const now = Date.now();
      const x = e.clientX != null ? e.clientX : (e.touches && e.touches[0] ? e.touches[0].clientX : -1);
      const y = e.clientY != null ? e.clientY : (e.touches && e.touches[0] ? e.touches[0].clientY : -1);
      const directTarget = e.target;

      // Gesture Deduplication: keep exactly one inspection transaction per user gesture
      if (now - lastHandledGestureTime < 350 && Math.abs(x - lastHandledCoords.x) < 15 && Math.abs(y - lastHandledCoords.y) < 15) {
        return;
      }
      lastHandledGestureTime = now;
      lastHandledCoords = { x: x, y: y };
      lastHandledTarget = directTarget;

      // Fast-path: check if this is an unambiguous, standard link with clean top layers
      if (isNormalUnambiguousLink(directTarget, x, y)) {
        // Fast return: do not block or prevent default, no delay added to normal browsing
        return;
      }

      const candidates = inspectClickCoordinates(x, y, directTarget);
      if (candidates.length === 0) return;

      const hasOverlay = candidates.some(function(c) { return c.isOverlay; });
      const intercepted = shouldInterceptClick(candidates, directTarget);

      if (intercepted) {
        // True Pre-Navigation Interception: halt default navigation and DOM event propagation
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
      }

      // Dispatch transparency inspection payload to background -> native bridge
      browser.runtime.sendMessage({
        type: 'CLICK_INSPECTED',
        candidates: candidates,
        hasOverlay: hasOverlay,
        intercepted: intercepted,
        pageUrl: window.location.href,
        timestamp: now
      }).catch(function() {});

    } catch (_e) {}
  }

  // Hook click in capturing phase BEFORE website event listeners or navigation can execute
  document.addEventListener('click', handleInteractionEvent, true);

})();
