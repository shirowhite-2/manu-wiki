`xwiki-tree-17.10.15.js` is the unmodified XWiki native tree module from:

https://github.com/xwiki/xwiki-platform/blob/xwiki-platform-17.10.15/xwiki-platform-core/xwiki-platform-tree/xwiki-platform-tree-webjar/src/main/webjar/tree.js

It is distributed under LGPL 2.1 or later, as stated in its source header;
the upstream license is included in `LICENSE-XWiki`.
The browser fixture loads this real module with jQuery, jsTree and Bootstrap 3.
Only the unused job runner and page-ready dependencies are stubbed; category
expansion, selection, keyboard handling and dropdown events use the actual libraries.
The fixture does not reproduce the production Flamingo theme.
