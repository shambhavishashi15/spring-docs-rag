package io.github.shambhavishashi15.springdocsrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HtmlSectionParserTest {

	private static final String PAGE_URL = "https://docs.spring.io/spring-boot/reference/sample.html";

	private static final String PAGE_TITLE = "Spring Boot: Sample Page";

	private List<Section> sections;

	@BeforeEach
	void parseFixture() throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/ingestion/sample-page.html")) {
			String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			sections = new HtmlSectionParser().parse(html, PAGE_URL, PAGE_TITLE);
		}
	}

	@Test
	void splitsIntoIntroAndOneSectionPerH2AndH3() {
		// "Only Subsections" has no text of its own, so it gets no section; its h3 does.
		assertThat(sections).extracting(Section::heading).containsExactly(
				"Sample Page",
				"Configuring Things",
				"Configuring Things > Nested Option",
				"Only Subsections > Child");
		assertThat(sections).allSatisfy(section -> {
			assertThat(section.pageTitle()).isEqualTo(PAGE_TITLE);
			assertThat(section.pageUrl()).isEqualTo(PAGE_URL);
		});
	}

	@Test
	void linksEachSectionToItsHeadingAnchor() {
		assertThat(sections).extracting(Section::url).containsExactly(
				PAGE_URL,
				PAGE_URL + "#sample.configuring",
				PAGE_URL + "#sample.configuring.nested",
				PAGE_URL + "#sample.only-subsections.child");
	}

	@Test
	void keepsSubsectionTextOutOfTheParentSection() {
		assertThat(sections.get(1).text()).contains("Configuring text.").doesNotContain("Nested text.");
	}

	@Test
	void keepsH4ContentInsideItsH3Section() {
		assertThat(sections.get(2).text()).isEqualTo("Nested text.\n\nDetail Heading\n\nDetail text.");
	}

	@Test
	void removesPageChromeAndDuplicateTabPanels() {
		String allText = String.join("\n", sections.stream().map(Section::text).toList());
		assertThat(allText).doesNotContain("Breadcrumb", "Previous page", "Next page", "Site navigation",
				"Java\n", "Kotlin", "kotlinService");
	}

	@Test
	void keepsCodeBlocksVerbatim() {
		assertThat(sections.get(0).text()).isEqualTo("""
				Intro paragraph about spring.profiles.active.

				@Bean
				MyService myService() {
				    return new MyService();
				}""");
	}

	@Test
	void labelsAdmonitions() {
		assertThat(sections.get(1).text()).isEqualTo("Configuring text.\n\nNote: Remember this.");
	}

	@Test
	void rejectsPagesWithoutAnAntoraArticle() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new HtmlSectionParser().parse("<html><body><p>Hi</p></body></html>", PAGE_URL, PAGE_TITLE))
				.withMessageContaining(PAGE_URL);
	}

}
