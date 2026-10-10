package io.github.shambhavishashi15.springdocsrag.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

// Turns an Antora-generated Spring docs page into sections split at h2/h3 headings.
@Component
public class HtmlSectionParser {

	// Page chrome inside <article>. Extra tab panels repeat the first panel's example
	// in another language or format (Kotlin, YAML), so only the first panel is kept.
	private static final String NOISE = ".breadcrumbs-container, nav.pagination, .tablist, .tabpanel ~ .tabpanel";

	// Elements that start a new paragraph in the extracted text.
	private static final Set<String> BLOCK_TAGS = Set.of(
			"p", "div", "pre", "table", "tr", "th", "td", "ul", "ol", "li", "dl", "dt", "dd", "h4", "h5", "h6");

	public List<Section> parse(String html, String pageUrl, String pageTitle) {
		Element article = Jsoup.parse(html, pageUrl).selectFirst("article.doc");
		if (article == null) {
			throw new IllegalArgumentException("No <article class=\"doc\"> in " + pageUrl);
		}
		article.select(NOISE).remove();
		labelAdmonitions(article);

		List<Section> sections = new ArrayList<>();
		Element preamble = article.selectFirst("#preamble");
		if (preamble != null) {
			add(sections, pageTitle, pageUrl, article.selectFirst("h1").text(), "", preamble);
		}
		for (Element sect1 : article.select("div.sect1")) {
			Element h2 = sect1.selectFirst("> h2");
			// The h2 section's own text is everything except its heading and its h3 subsections.
			Element ownContent = sect1.clone();
			ownContent.select("> h2, div.sect2").remove();
			add(sections, pageTitle, pageUrl, h2.text(), h2.id(), ownContent);

			for (Element sect2 : sect1.select("div.sect2")) {
				Element h3 = sect2.selectFirst("> h3");
				Element content = sect2.clone();
				content.select("> h3").remove();
				// "h2 > h3" keeps citations unambiguous when h3 titles repeat across sections.
				add(sections, pageTitle, pageUrl, h2.text() + " > " + h3.text(), h3.id(), content);
			}
		}
		return sections;
	}

	private static void add(List<Section> sections, String pageTitle, String pageUrl,
			String heading, String anchor, Element content) {
		String text = toText(content);
		if (!text.isEmpty()) {
			sections.add(new Section(pageTitle, pageUrl, heading, anchor, text));
		}
	}

	// Admonitions render their label ("Note", "Warning") only as an icon title; keep it as text.
	private static void labelAdmonitions(Element article) {
		for (Element icon : article.select(".admonitionblock td.icon")) {
			Element label = icon.selectFirst("[title]");
			Element content = icon.nextElementSibling();
			if (label != null && content != null) {
				content.prependText(label.attr("title") + ": ");
			}
			icon.remove();
		}
	}

	// Like Element.text(), but keeps block boundaries as blank lines and <pre> code verbatim.
	private static String toText(Element root) {
		StringBuilder out = new StringBuilder();
		root.traverse(new NodeVisitor() {

			private int preDepth;

			@Override
			public void head(Node node, int depth) {
				if (node instanceof TextNode text) {
					if (preDepth > 0) {
						out.append(text.getWholeText());
					}
					else {
						appendCollapsed(out, text.text());
					}
				}
				else if (node instanceof Element element) {
					if (element.normalName().equals("pre")) {
						preDepth++;
					}
					if (element.normalName().equals("br")) {
						out.append('\n');
					}
					else if (BLOCK_TAGS.contains(element.normalName())) {
						paragraphBreak(out);
					}
				}
			}

			@Override
			public void tail(Node node, int depth) {
				if (node instanceof Element element) {
					if (element.normalName().equals("pre")) {
						preDepth--;
					}
					if (BLOCK_TAGS.contains(element.normalName())) {
						paragraphBreak(out);
					}
				}
			}

		});
		return out.toString().strip();
	}

	// jsoup normalises whitespace within one text node; this also collapses it across adjacent
	// nodes and avoids leading spaces on a new line.
	private static void appendCollapsed(StringBuilder out, String text) {
		boolean afterWhitespace = out.isEmpty() || Character.isWhitespace(out.charAt(out.length() - 1));
		out.append(afterWhitespace ? text.stripLeading() : text);
	}

	// Ends the current paragraph with exactly one blank line, dropping trailing spaces.
	private static void paragraphBreak(StringBuilder out) {
		while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
			out.setLength(out.length() - 1);
		}
		if (out.isEmpty() || endsWithBlankLine(out)) {
			return;
		}
		out.append(out.charAt(out.length() - 1) == '\n' ? "\n" : "\n\n");
	}

	private static boolean endsWithBlankLine(StringBuilder out) {
		int length = out.length();
		return length >= 2 && out.charAt(length - 1) == '\n' && out.charAt(length - 2) == '\n';
	}

}
