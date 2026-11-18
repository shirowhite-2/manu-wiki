(function () {
  'use strict';
  if (window.LuxPediaI18n) { return; }
  var messages = __LUXPEDIA_MESSAGES__;
  var key = 'luxpedia.interface.language';
  var requested = new URLSearchParams(location.search).get('xwikilanguage');
  var saved;
  try { saved = localStorage.getItem(key); } catch (error) { /* storage is optional */ }
  var language = requested === 'zh' || requested === 'en' ? requested : saved === 'zh' ? 'zh' : 'en';
  if (requested === 'zh' || requested === 'en') {
    try { localStorage.setItem(key, language); } catch (error) { /* storage is optional */ }
  }
  function t(name) { return messages[language][name] || messages.en[name] || name; }
  function editing() { return /\/bin\/(?:edit|inline|create)\//.test(location.pathname); }
  window.LuxPediaI18n = { t: t, language: function () { return language; } };
  function update() {
    document.querySelectorAll('[data-luxpedia-i18n]').forEach(function (node) {
      var value = t(node.dataset.luxpediaI18n);
      if (node.textContent !== value) { node.textContent = value; }
    });
  }
  function install() {
    if (document.getElementById('luxpedia-language')) { return; }
    var navigation = document.querySelector('#menuview .navbar-right, #headerglobal .navbar-right');
    var header = document.querySelector('#headerglobal, #header');
    var container = document.createElement(navigation && navigation.tagName === 'UL' ? 'li' : 'div');
    container.id = 'luxpedia-language';
    container.className = navigation ? 'navbar-form' : 'form-inline text-right';
    var label = document.createElement('label');
    label.htmlFor = 'luxpedia-language-select';
    label.className = 'control-label';
    label.dataset.luxpediaI18n = 'language';
    var select = document.createElement('select');
    select.id = 'luxpedia-language-select';
    select.className = 'form-control input-sm';
    select.style.cssText = 'width:auto;display:inline-block;';
    [['en', 'English'], ['zh', '中文']].forEach(function (item) {
      var option = document.createElement('option');
      option.value = item[0]; option.textContent = item[1]; select.appendChild(option);
    });
    select.value = language;
    container.appendChild(label); container.appendChild(document.createTextNode(' ')); container.appendChild(select);
    var notice = document.createElement('span');
    notice.setAttribute('role', 'status');
    container.appendChild(notice);
    if (navigation) { navigation.appendChild(container); }
    else if (header) { header.appendChild(container); }
    else { document.body.insertBefore(container, document.body.firstChild); }
    select.addEventListener('change', function () {
      language = select.value;
      try { localStorage.setItem(key, language); } catch (error) { /* storage is optional */ }
      update();
      document.dispatchEvent(new CustomEvent('luxpedia:languagechange'));
      var url = new URL(location.href);
      url.searchParams.set('xwikilanguage', language);
      if (editing()) {
        // Keep unsaved editor text. XWiki sets its locale cookie on this safe GET.
        notice.textContent = t('languageAfterSave');
        fetch('/bin/view/Main/?xwikilanguage=' + language, { credentials: 'same-origin', cache: 'no-store' });
      } else { location.assign(url.href); }
    });
    update();
    // A saved browser preference and XWiki's locale cookie can disagree.
    // Reconcile native menus on safe view pages, once, without losing form input.
    var serverLanguage = (document.documentElement.lang || '').toLowerCase().replace('_', '-').split('-')[0];
    if (!requested && serverLanguage && serverLanguage !== language) {
      if (editing()) {
        notice.textContent = t('languageAfterSave');
        fetch('/bin/view/Main/?xwikilanguage=' + language, { credentials: 'same-origin', cache: 'no-store' });
      } else {
        var localized = new URL(location.href);
        localized.searchParams.set('xwikilanguage', language);
        location.replace(localized.href);
      }
    }
  }
  document.addEventListener('luxpedia:languagechange', update);
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', install); }
  else { install(); }
})();
