package io.github.shambhavishashi15.springdocsrag.ingestion;

// One citable part of a docs page: the text under a single h2/h3 heading (or the page intro).
public record Section(String pageTitle, String pageUrl, String heading, String anchor, String text) {

	// Deep link to the section; the intro has no anchor, so it links to the page itself.
	public String url() {
		return anchor.isEmpty() ? pageUrl : pageUrl + "#" + anchor;
	}

}
