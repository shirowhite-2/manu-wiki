package org.luxpedia.workflow;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xwiki.rendering.block.*;
import org.xwiki.rendering.listener.reference.ResourceReference;

/** Article body as plain text for LightRAG; images, attachments, media and executable macros never leave the wiki. */
final class RagTextExtractor {
    /** Macros whose body is ordinary article prose. Everything else (velocity, html, include, embed, drawio...) is dropped. */
    static final Set<String> TEXT_MACROS = Set.of("info", "warning", "error", "success", "box", "code", "quote", "message", "content", "translation");
    static final Set<String> BLOCKED_LINKS = Set.of("attach", "pageAttach", "data", "path", "unc", "icon");
    private static final Pattern DATA_URI = Pattern.compile("data:[a-z0-9.+/-]*;base64,[A-Za-z0-9+/=\\s]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE64_RUN = Pattern.compile("[A-Za-z0-9+/=]{200,}");
    private static final Pattern MACRO_PAIR = Pattern.compile("\\{\\{([A-Za-z0-9_-]+)([^}]*)}}(.*?)\\{\\{/\\1}}", Pattern.DOTALL);
    private static final Pattern MACRO_SINGLE = Pattern.compile("\\{\\{[A-Za-z0-9_/-][^}]*}}");
    private static final Pattern IMAGE = Pattern.compile("\\[\\[\\s*image:[^]]*]]|(?<![\\w\\[])image:\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTACHMENT_LINK = Pattern.compile("\\[\\[(?:[^]]*?>>)?\\s*(?:attach|pageAttach|data):[^]]*]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELLED_LINK = Pattern.compile("\\[\\[(.+?)>>[^]]*]]");
    private static final Pattern BARE_LINK = Pattern.compile("\\[\\[([^]|>]+)(?:\\|\\|[^]]*)?]]");
    private static final Pattern PARAMETERS = Pattern.compile("\\(%.*?%\\)", Pattern.DOTALL);
    private static final Pattern VERBATIM = Pattern.compile("\\{\\{\\{(.*?)}}}", Pattern.DOTALL);
    private static final Pattern FORMATTING = Pattern.compile("\\*\\*|//|__|--(?=\\S)|\\^\\^|,,|##");

    private RagTextExtractor() { }

    static String text(XDOM xdom) {
        var out = new StringBuilder();
        for (Block block : xdom.getChildren()) { walk(block, out); }
        return tidy(out.toString());
    }

    private static void walk(Block block, StringBuilder out) {
        if (block instanceof ImageBlock || block instanceof RawBlock) { return; }
        if (block instanceof LinkBlock link) {
            ResourceReference target = link.getReference();
            if (target == null || BLOCKED_LINKS.contains(target.getType().getScheme())) { return; }
            if (link.getChildren().isEmpty()) { out.append(target.getReference()); return; }
            children(block, out); return;
        }
        if (block instanceof AbstractMacroBlock macro) {
            if (!TEXT_MACROS.contains(macro.getId().toLowerCase(Locale.ROOT))) { return; }
            if (!macro.getChildren().isEmpty()) { children(block, out); }
            else if (macro.getContent() != null) {
                paragraph(out); out.append(macro.getId().equalsIgnoreCase("code") ? macro.getContent() : clean(macro.getContent())); paragraph(out);
            }
            return;
        }
        if (block instanceof WordBlock word) { out.append(word.getWord()); return; }
        if (block instanceof SpaceBlock) { out.append(' '); return; }
        if (block instanceof SpecialSymbolBlock symbol) { out.append(symbol.getSymbol()); return; }
        if (block instanceof NewLineBlock || block instanceof EmptyLinesBlock) { out.append('\n'); return; }
        if (block instanceof VerbatimBlock verbatim) { out.append(verbatim.getProtectedString()); return; }
        if (block instanceof HorizontalLineBlock) { paragraph(out); out.append("---"); paragraph(out); return; }
        if (block instanceof HeaderBlock header) {
            paragraph(out); out.append("#".repeat(Math.max(1, Math.min(6, header.getLevel().getAsInt())))).append(' ');
            children(block, out); paragraph(out); return;
        }
        if (block instanceof ListItemBlock || block instanceof DefinitionTermBlock || block instanceof DefinitionDescriptionBlock) {
            line(out); out.append("- "); children(block, out); line(out); return;
        }
        if (block instanceof TableRowBlock) { line(out); out.append("| "); children(block, out); line(out); return; }
        if (block instanceof TableCellBlock) { children(block, out); out.append(" | "); return; }
        if (block instanceof ParagraphBlock || block instanceof SectionBlock || block instanceof GroupBlock ||
            block instanceof ListBLock || block instanceof QuotationBlock || block instanceof TableBlock ||
            block instanceof DefinitionListBlock || block instanceof FigureBlock) {
            paragraph(out); children(block, out); paragraph(out); return;
        }
        if (block instanceof QuotationLineBlock || block instanceof FigureCaptionBlock) { line(out); children(block, out); line(out); return; }
        children(block, out);
    }

    private static void children(Block block, StringBuilder out) { for (Block child : block.getChildren()) { walk(child, out); } }
    private static void line(StringBuilder out) { if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') { out.append('\n'); } }
    private static void paragraph(StringBuilder out) { line(out); if (out.length() > 0 && !out.toString().endsWith("\n\n")) { out.append('\n'); } }

    /** Best-effort XWiki 2.1 markup cleanup for unparsed macro bodies and pages whose XDOM is unavailable. */
    static String clean(String markup) {
        if (markup == null) { return ""; }
        String text = markup.replace("\r\n", "\n");
        Matcher pairs = MACRO_PAIR.matcher(text); var kept = new StringBuilder();
        while (pairs.find()) {
            String id = pairs.group(1).toLowerCase(Locale.ROOT);
            pairs.appendReplacement(kept, Matcher.quoteReplacement(TEXT_MACROS.contains(id) ? "\n" + clean(pairs.group(3)) + "\n" : ""));
        }
        pairs.appendTail(kept); text = kept.toString();
        text = MACRO_SINGLE.matcher(text).replaceAll("");
        text = VERBATIM.matcher(text).replaceAll("$1");
        text = IMAGE.matcher(text).replaceAll("");
        text = ATTACHMENT_LINK.matcher(text).replaceAll("");
        text = LABELLED_LINK.matcher(text).replaceAll("$1");
        text = BARE_LINK.matcher(text).replaceAll("$1");
        text = PARAMETERS.matcher(text).replaceAll("");
        text = text.replaceAll("(?m)^\\s*(=+)\\s*(.*?)\\s*=*\\s*$", "$1 $2").replaceAll("(?m)^(=+) ", "# ");
        text = text.replace("|=", "| ");
        text = FORMATTING.matcher(text).replaceAll("");
        return tidy(text);
    }

    static String tidy(String text) {
        String result = DATA_URI.matcher(text).replaceAll("");
        result = BASE64_RUN.matcher(result).replaceAll("");
        result = result.replace('\0', ' ').replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n");
        return result.strip();
    }
}
