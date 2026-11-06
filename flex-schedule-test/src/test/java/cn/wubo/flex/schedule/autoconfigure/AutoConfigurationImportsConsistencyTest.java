package cn.wubo.flex.schedule.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6 scenario C9: the
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * registration file must stay consistent with the actual auto-configuration
 * classes — every listed entry resolves on the classpath, carries
 * {@link AutoConfiguration}, the roster equals exactly the five known
 * fragments (pinned below), and the file contains no duplicates or stray
 * blank/comment lines.
 */
class AutoConfigurationImportsConsistencyTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /**
     * Pinned roster. Any addition/removal of a flex-schedule auto-configuration
     * must update BOTH this list and the imports file — that is the point of the test.
     */
    private static final List<String> EXPECTED_ROSTER = List.of(
            "cn.wubo.flex.schedule.autoconfigure.FlexScheduleAutoConfiguration",
            "cn.wubo.flex.schedule.autoconfigure.FlexScheduleEndpointAutoConfiguration",
            "cn.wubo.flex.schedule.autoconfigure.FlexScheduleMetricsAutoConfiguration",
            "cn.wubo.flex.schedule.autoconfigure.FlexScheduleHealthAutoConfiguration",
            "cn.wubo.flex.schedule.autoconfigure.FlexScheduleRedisLockAutoConfiguration");

    @Test
    void importsFile_isOnClasspathAndParsesToTheExpectedRoster() throws IOException {
        List<String> lines = readImportsLines();

        assertThat(lines)
                .as("imports file must equal the pinned 5-class roster, in file order")
                .isEqualTo(EXPECTED_ROSTER);
        assertThat(lines).doesNotHaveDuplicates();
    }

    @Test
    void importsFile_hasNoBlankOrCommentLines() throws IOException {
        // The file format allows # comments and blanks, but this registration file is
        // intentionally kept bare (5 plain FQCNs); pin that so blank/comment noise,
        // leading/trailing whitespace, or empty entries can never sneak in.
        List<String> lines = readRawLines();
        for (String line : lines) {
            assertThat(line)
                    .as("every line must be a non-blank, non-comment, whitespace-free FQCN")
                    .isNotBlank()
                    .doesNotStartWith("#")
                    .isEqualTo(line.trim());
        }
    }

    @Test
    void everyImportedClass_resolvesAndCarriesAutoConfigurationAnnotation() throws IOException {
        for (String className : readImportsLines()) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("imports file lists unresolvable class " + className, e);
            }
            AutoConfiguration annotation = type.getAnnotation(AutoConfiguration.class);
            assertThat(annotation)
                    .as("%s must be annotated @AutoConfiguration to belong in the imports file", className)
                    .isNotNull();
        }
    }

    private List<String> readImportsLines() throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : readRawLines()) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private List<String> readRawLines() throws IOException {
        ClassLoader classLoader = AutoConfigurationImportsConsistencyTest.class.getClassLoader();
        try (InputStream in = classLoader.getResourceAsStream(IMPORTS_RESOURCE)) {
            assertThat(in)
                    .as("imports resource %s must be on the test classpath (packaged by " +
                            "flex-schedule-spring-boot-autoconfigure via the starter)", IMPORTS_RESOURCE)
                    .isNotNull();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        }
    }
}
