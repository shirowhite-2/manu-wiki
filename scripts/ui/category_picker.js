  // Use the same XWiki xtree / Bootstrap dropdown as the Flamingo breadcrumb.
  // Only view-filtered category entries from Knowledge.CategoryIndex feed the tree.
  var categoryTreeState = null;

  function categoryFieldOption(field, key, options) {
    return Array.from(field.options).find(function (option) {
      var entry = matchCategoryOption(option.value, option.textContent.trim(), options);
      return entry ? entry.key === key : key === '__root__' && isRootCategory(option.value);
    });
  }

  function categoryTreeData(field, options) {
    var selected = selectedCategory();
    var root = { id: 'publication-category-root', text: t('knowledge'),
      data: { key: '__root__' }, state: { opened: true,
        selected: selected && selected.key === '__root__', disabled: !categoryFieldOption(field, '__root__', options) },
      children: [] };
    var nodes = new Map();
    options.forEach(function (entry) {
      nodes.set(entry.key, { id: 'publication-category-' + encodeURIComponent(entry.key), text: entry.label,
        data: { key: entry.key }, state: { selected: selected && selected.key === entry.key,
          disabled: !categoryFieldOption(field, entry.key, options) }, children: [] });
    });
    options.forEach(function (entry) {
      var parent = entry.key.split('.'); parent.pop();
      while (parent.length && !nodes.has(parent.join('.'))) { parent.pop(); }
      (nodes.get(parent.join('.')) || root).children.push(nodes.get(entry.key));
      // Reveal a saved nested selection, without expanding every category.
      if (selected && selected.key && selected.key.startsWith(entry.key + '.')) {
        nodes.get(entry.key).state.opened = true;
      }
    });
    return [root];
  }

  function refreshCategoryTree() {
    var state = categoryTreeState;
    if (!state || !state.field.isConnected || !state.tree.isConnected) { return; }
    var selected = selectedCategory();
    var label = selected && !selected.invalid ? selected.key === '__root__' ? t('knowledge') : selected.label : t('chooseCategory');
    text(state.label, label);
    state.toggle.setAttribute('aria-label', t('browseCategories'));
    text(state.hint, t('categoryHint'));
    if (state.error.textContent) { text(state.error, t('categoryTreeUnavailable')); }
    if (state.instance) {
      var root = state.instance.get_node('publication-category-root');
      if (root && root.text !== t('knowledge')) { state.instance.set_text(root, t('knowledge')); }
    }
  }

  function installCategoryTree(field, options) {
    if (categoryTreeState && categoryTreeState.field === field && categoryTreeState.tree.isConnected) { return; }
    if (categoryTreeState && categoryTreeState.instance) { categoryTreeState.instance.destroy(); }
    var container = document.getElementById('luxpedia-category-choice');
    if (!container) {
      container = document.createElement('div'); container.id = 'luxpedia-category-choice'; container.className = 'xform';
      var editor = document.getElementById('xwikieditor');
      if (!editor || !editor.parentNode) { return; }
      editor.parentNode.insertBefore(container, editor);
    }
    // Keep the existing property as the backing value; preserve native save hooks.
    field.hidden = true;
    if (field.id === 'luxpedia-publish-category') { container.appendChild(field); }
    document.querySelectorAll('label[for]').forEach(function (label) {
      if (label.htmlFor === field.id) { label.hidden = true; }
    });
    Array.from(container.children).forEach(function (child) {
      if (!child.contains(field)) { child.remove(); }
    });
    var heading = document.createElement('div');
    heading.dataset.luxpediaI18n = 'chooseCategory'; heading.textContent = t('chooseCategory');
    var breadcrumb = document.createElement('ol'); breadcrumb.className = 'breadcrumb';
    var item = document.createElement('li'); item.className = 'dropdown';
    var label = document.createElement('span'); label.id = 'luxpedia-category-selected';
    var toggle = document.createElement('button'); toggle.type = 'button'; toggle.id = 'luxpedia-category-toggle';
    toggle.className = 'dropdown-toggle'; toggle.setAttribute('data-toggle', 'dropdown');
    toggle.disabled = true;
    toggle.setAttribute('aria-haspopup', 'true'); toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-controls', 'luxpedia-category-tree');
    toggle.innerHTML = '<span class="caret" aria-hidden="true"></span>';
    var menu = document.createElement('div'); menu.className = 'dropdown-menu';
    menu.style.cssText = 'max-height:24em;overflow:auto;max-width:calc(100vw - 3em);';
    var tree = document.createElement('div'); tree.id = 'luxpedia-category-tree';
    tree.className = 'breadcrumb-tree jstree-no-links';
    tree.setAttribute('data-responsive', 'true'); tree.setAttribute('data-icons', 'false');
    var error = document.createElement('p'); error.className = 'xHint'; error.setAttribute('role', 'alert');
    error.id = 'luxpedia-category-tree-error'; error.hidden = true;
    var hint = document.getElementById('luxpedia-category-hint') || document.createElement('div');
    hint.id = 'luxpedia-category-hint'; hint.className = 'xHint';
    hint.dataset.luxpediaI18n = 'categoryHint'; toggle.setAttribute('aria-describedby', hint.id);
    menu.appendChild(tree);
    item.appendChild(label); item.appendChild(toggle); item.appendChild(menu); breadcrumb.appendChild(item);
    container.appendChild(heading); container.appendChild(breadcrumb); container.appendChild(hint); container.appendChild(error);
    var state = { field: field, tree: tree, label: label, toggle: toggle, hint: hint, error: error, instance: null };
    categoryTreeState = state;
    field.addEventListener('change', refreshCategoryTree);
    refreshCategoryTree();

    function unavailable() {
      if (categoryTreeState !== state) { return; }
      field.dataset.categoryTreeUnavailable = 'true';
      error.textContent = t('categoryTreeUnavailable');
      error.hidden = false;
      toggle.disabled = false;
    }
    if (typeof window.require !== 'function') { unavailable(); return; }
    window.require(['xwiki-tree', 'bootstrap'], function ($) {
      if (categoryTreeState !== state || !tree.isConnected) { return; }
      if (!$ || !$.fn.xtree || !$.fn.dropdown) { unavailable(); return; }
      delete field.dataset.categoryTreeUnavailable;
      toggle.disabled = false;
      error.textContent = '';
      error.hidden = true;
      // Expansion stays inside the picker. Selecting a category closes it without navigating.
      $(menu).on('click', function (event) { event.stopPropagation(); });
      $(item).on('shown.bs.dropdown', function () {
        if (state.instance || state.loading) { return; }
        state.loading = true;
        $(tree).on('select_node.jstree', function (event, data) {
          if (!data.event) { return; }
          var key = data.node.data && data.node.data.key;
          var option = categoryFieldOption(field, key, options);
          if (!option) { return; }
          field.value = option.value;
          field.dispatchEvent(new Event('change', { bubbles: true }));
          $(toggle).dropdown('toggle'); toggle.focus();
        }).one('ready.jstree', function (event, data) {
          state.instance = data.instance;
          state.loading = false;
        }).xtree({ core: { data: categoryTreeData(field, options), multiple: false, force_text: true,
          check_callback: false }, plugins: [] });
      });
    }, unavailable);
  }
