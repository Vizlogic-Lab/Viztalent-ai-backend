package com.smartstaff.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionBankFileParserTest {

    private final QuestionBankFileParser parser = new QuestionBankFileParser(new ObjectMapper());

    private QuestionBankFileParser.ParseResult csv(String text) throws IOException {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8), "bank.csv");
    }

    @Test
    @DisplayName("CSV: quoted fields may contain commas, and 'correct_index' accepts a letter")
    void csvQuotedFieldsAndLetterAnswers() throws IOException {
        var result = csv("""
                type,level,skill,difficulty,question,options,correct_index
                mcq,l1,Java,easy,"What is 2+2, roughly?",3|4|5,B
                """);

        assertThat(result.warnings()).isEmpty();
        var q = result.questions().get(0);
        assertThat(q.type()).isEqualTo("MCQ");
        assertThat(q.level()).isEqualTo("L1");
        assertThat(q.difficulty()).isEqualTo("EASY");
        assertThat(q.prompt()).isEqualTo("What is 2+2, roughly?");
        assertThat(q.options()).containsExactly("3", "4", "5");
        assertThat(q.correctIndices()).containsExactly(1);
    }

    @Test
    @DisplayName("MSQ can have several correct answers, as numbers or letters, comma- or pipe-separated")
    void multiSelectAnswers() throws IOException {
        var result = csv("""
                type,question,options,correct_index
                msq,Pick the HTTP methods,GET|POST|FETCH|DELETE,"0,1,3"
                msq,Pick again,a|b|c,A|C
                """);

        assertThat(result.questions().get(0).correctIndices()).containsExactly(0, 1, 3);
        assertThat(result.questions().get(1).correctIndices()).containsExactly(0, 2);
    }

    @Test
    @DisplayName("descriptive and coding questions need no options")
    void freeFormTypes() throws IOException {
        var result = csv("""
                type,question
                coding,Reverse a string.
                descriptive,Explain the CAP theorem.
                """);

        assertThat(result.questions()).extracting(QuestionBankFileParser.ParsedQuestion::type)
                .containsExactly("CODING", "DESCRIPTIVE");
    }

    @Test
    @DisplayName("bad rows are skipped with a warning; good rows still load")
    void badRowsSkippedNotFatal() throws IOException {
        var result = csv("""
                type,question,options,correct_index
                mcq,Good one,a|b,0
                weird,Unknown type,a|b,0
                mcq,,a|b,0
                mcq,No options here,,0
                """);

        assertThat(result.questions()).hasSize(1);
        assertThat(result.warnings()).hasSize(3);
    }

    @Test
    @DisplayName("out-of-range answer indices are dropped rather than stored")
    void outOfRangeAnswer() throws IOException {
        var result = csv("""
                type,question,options,correct_index
                mcq,Q,a|b,7
                """);
        assertThat(result.questions().get(0).correctIndices()).isEmpty();
    }

    @Test
    @DisplayName("JSON arrays work, with options as a real array")
    void json() throws IOException {
        String body = "[{\"type\":\"mcq\",\"question\":\"Capital of France?\",\"options\":[\"Paris\",\"Rome\"],\"correct_index\":0,\"level\":\"L2\"}]";
        var result = parser.parse(body.getBytes(StandardCharsets.UTF_8), "bank.json");

        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).options()).containsExactly("Paris", "Rome");
        assertThat(result.questions().get(0).level()).isEqualTo("L2");
    }

    @Test
    @DisplayName("unsupported extensions and non-array JSON are rejected with a clear error")
    void rejections() {
        assertThatThrownBy(() -> parser.parse(new byte[0], "bank.pdf")).isInstanceOf(IOException.class).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> parser.parse("{}".getBytes(StandardCharsets.UTF_8), "bank.json")).isInstanceOf(IOException.class).hasMessageContaining("array");
    }
}
