// Remmi WebRTC & Media Device Leak Hardening (Content Script Layer)
// Protects IP leaks via WebRTC RTCPeerConnection and media device enumeration
(function() {
  'use strict';
  try {
    if (typeof window !== 'undefined') {
      // Neutralize RTCPeerConnection
      if (window.RTCPeerConnection) {
        window.RTCPeerConnection = function() {
          throw new Error('WebRTC disabled by Remmi Security Policy');
        };
      }
      if (window.webkitRTCPeerConnection) {
        window.webkitRTCPeerConnection = function() {
          throw new Error('WebRTC disabled by Remmi Security Policy');
        };
      }
      if (window.mozRTCPeerConnection) {
        window.mozRTCPeerConnection = function() {
          throw new Error('WebRTC disabled by Remmi Security Policy');
        };
      }
      // Neutralize RTCDataChannel
      if (window.RTCDataChannel) {
        window.RTCDataChannel = function() {
          throw new Error('RTCDataChannel disabled by Remmi Security Policy');
        };
      }
    }
  } catch (_e) {}
})();
