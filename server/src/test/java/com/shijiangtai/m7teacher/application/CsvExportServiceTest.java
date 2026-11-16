package com.shijiangtai.m7teacher.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvExportServiceTest {

    @Test
    void prefixesFormulaInjectionCharacters() {
        assertThat(CsvExportService.escape("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvExportService.escape("+SUM(A1)")).isEqualTo("'+SUM(A1)");
        assertThat(CsvExportService.escape("-2")).isEqualTo("'-2");
        assertThat(CsvExportService.escape("@cmd")).isEqualTo("'@cmd");
    }

    @Test
    void quotesRfc4180SpecialCharacters() {
        assertThat(CsvExportService.escape("张三,李四")).isEqualTo("\"张三,李四\"");
        assertThat(CsvExportService.escape("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(CsvExportService.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }

    @Test
    void leavesPlainTextUntouched() {
        assertThat(CsvExportService.escape("荷塘月色")).isEqualTo("荷塘月色");
        assertThat(CsvExportService.escape("")).isEmpty();
    }
}
