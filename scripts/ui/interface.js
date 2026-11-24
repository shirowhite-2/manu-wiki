(function () {
  'use strict';
  if (window.LuxPediaI18n) { return; }
  var messages = __LUXPEDIA_MESSAGES__;
  var language = 'en';
  function t(name) { return messages.en[name] || name; }
  function editing() { return /\/bin\/(?:edit|inline|create|admin)\//.test(location.pathname); }
  window.LuxPediaI18n = { t: t, language: function () { return language; } };
  function update() {
    // Keep the native inputs and their autosave listeners; distinguish the view and edit switches.
    [['authenticate_view', 'guestViewRestriction'], ['authenticate_edit', 'guestEditRestriction']].forEach(function (entry) {
      document.querySelectorAll('label[for="' + entry[0] + '"]').forEach(function (label) {
        var input = label.querySelector('input'); if (!input) { return; }
        var caption = label.querySelector('[data-luxpedia-auth-caption]');
        if (!caption) {
          caption = document.createElement('span'); caption.dataset.luxpediaAuthCaption = '1';
          Array.from(label.childNodes).forEach(function (child) { if (child !== input) { child.remove(); } });
          label.appendChild(document.createTextNode(' ')); label.appendChild(caption);
        }
        caption.dataset.luxpediaI18n = entry[1];
      });
    });
    document.querySelectorAll('[data-luxpedia-i18n]').forEach(function (node) {
      var value = t(node.dataset.luxpediaI18n);
      if (node.textContent !== value) { node.textContent = value; }
    });
  }
  function enforceEnglishLocale() {
    var requested = new URLSearchParams(location.search).get('xwikilanguage');
    var serverLanguage = (document.documentElement.lang || '').toLowerCase().replace('_', '-').split('-')[0];
    if (requested && requested !== 'en' || serverLanguage && serverLanguage !== 'en') {
      if (editing()) {
        // Preserve unsaved content; set the locale for the next safe page request.
        fetch('/bin/view/Main/?xwikilanguage=en', { credentials: 'same-origin', cache: 'no-store' });
      } else {
        var english = new URL(location.href); english.searchParams.set('xwikilanguage', 'en');
        location.replace(english.href);
      }
    }
  }
  function install() {
    // Remove an old switch left in a cached DOM and keep the interface English-only.
    var languageMenu = document.getElementById('luxpedia-language');
    if (languageMenu) { languageMenu.remove(); }
    update();
    enforceEnglishLocale();
  }
  document.addEventListener('luxpedia:languagechange', update);
  document.addEventListener('xwiki:dom:updated', update);
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', install); }
  else { install(); }
})();
