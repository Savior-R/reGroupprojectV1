package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.domain.ActiveStatus;
import com.shijiangtai.m1auth.domain.Major;
import com.shijiangtai.m1auth.domain.OrganizationRepository;
import com.shijiangtai.m1auth.domain.OrganizationRepository.ClassImportRow;
import com.shijiangtai.m1auth.domain.SchoolClass;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织导入（FR-M1-04、03 §3.6）。
 *
 * <ul>
 *   <li>CSV / XLSX；单文件 ≤ 500 行、≤ 5 MB
 *   <li>专业列：{@code name}；班级列：{@code major_name, grade, name, status?}
 *   <li>整批事务：任一行校验失败则整批不写入，返回 errors（含行号 / 列 / 原因）
 *   <li>按自然键幂等 upsert：学校内专业名；专业 + 年级 + 班名
 * </ul>
 */
@Service
public class OrgImportService {

    private static final int MAX_ROWS = 500;
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final OrganizationRepository organization;
    private final SchoolApplicationService schools;
    private final Clock clock;

    public OrgImportService(
            OrganizationRepository organization,
            SchoolApplicationService schools,
            Clock clock) {
        this.organization = organization;
        this.schools = schools;
        this.clock = clock;
    }

    /** 导入专业。 */
    @Transactional
    public ImportResult importMajors(M1Principal principal, InputStream input, String filename) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        List<RawRow> rows = parse(input, filename);

        List<ImportError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RawRow row : rows) {
            String name = row.value("name");
            if (name == null || name.isBlank()) {
                errors.add(new ImportError(row.lineNumber(), "name", "专业名称不能为空"));
                continue;
            }
            if (name.trim().length() > Major.MAX_NAME_LENGTH) {
                errors.add(new ImportError(
                        row.lineNumber(), "name",
                        "专业名称不能超过 " + Major.MAX_NAME_LENGTH + " 个字符"));
                continue;
            }
            if (!seen.add(name.trim())) {
                errors.add(new ImportError(row.lineNumber(), "name", "专业名称在本批次内重复"));
            }
        }
        failIfAny(errors, rows.size());

        organization.upsertMajors(
                schoolId, rows.stream().map(row -> row.value("name")).toList(), clock.instant());
        return new ImportResult(rows.size(), rows.size(), 0, List.of());
    }

    /** 导入班级；{@code major_name} 必须是本校已存在且启用的专业。 */
    @Transactional
    public ImportResult importClasses(M1Principal principal, InputStream input, String filename) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        List<RawRow> rows = parse(input, filename);

        Map<String, Major> majorsByName = new java.util.HashMap<>();
        organization.listMajors(schoolId, false)
                .forEach(major -> majorsByName.put(major.name(), major));

        List<ImportError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ClassImportRow> accepted = new ArrayList<>();

        for (RawRow row : rows) {
            String majorName = row.value("major_name");
            String grade = row.value("grade");
            String name = row.value("name");
            String status = row.value("status");

            if (majorName == null || majorName.isBlank()) {
                errors.add(new ImportError(row.lineNumber(), "major_name", "专业名称不能为空"));
                continue;
            }
            Major major = majorsByName.get(majorName);
            if (major == null) {
                errors.add(new ImportError(
                        row.lineNumber(), "major_name", "专业不存在或已停用: " + majorName));
                continue;
            }
            if (!major.isActive()) {
                errors.add(new ImportError(
                        row.lineNumber(), "major_name", "专业已停用: " + majorName));
                continue;
            }
            if (grade == null || grade.isBlank()) {
                errors.add(new ImportError(row.lineNumber(), "grade", "年级不能为空"));
                continue;
            }
            if (grade.trim().length() > SchoolClass.MAX_GRADE_LENGTH) {
                errors.add(new ImportError(
                        row.lineNumber(), "grade",
                        "年级不能超过 " + SchoolClass.MAX_GRADE_LENGTH + " 个字符"));
                continue;
            }
            if (name == null || name.isBlank()) {
                errors.add(new ImportError(row.lineNumber(), "name", "班名不能为空"));
                continue;
            }
            if (name.trim().length() > SchoolClass.MAX_NAME_LENGTH) {
                errors.add(new ImportError(
                        row.lineNumber(), "name",
                        "班名不能超过 " + SchoolClass.MAX_NAME_LENGTH + " 个字符"));
                continue;
            }
            String naturalKey = majorName + "|" + grade + "|" + name;
            if (!seen.add(naturalKey)) {
                errors.add(new ImportError(
                        row.lineNumber(), "name", "同一专业年级下班名在批次内重复"));
                continue;
            }
            String normalizedStatus = status == null || status.isBlank()
                    ? ActiveStatus.ACTIVE.dbValue() : status.trim().toLowerCase(Locale.ROOT);
            if (!normalizedStatus.equals("active") && !normalizedStatus.equals("inactive")) {
                errors.add(new ImportError(
                        row.lineNumber(), "status", "status 只能是 active 或 inactive"));
                continue;
            }
            accepted.add(new ClassImportRow(majorName, grade.trim(), name.trim(), normalizedStatus));
        }
        failIfAny(errors, rows.size());

        organization.upsertClasses(schoolId, accepted, clock.instant());
        return new ImportResult(rows.size(), accepted.size(), 0, List.of());
    }

    /** 导入模板；{@code type=major|class}，返回带 BOM 的 CSV 便于 Excel 正确识别中文。 */
    public byte[] template(String type) {
        String csv = switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "major" -> "name\n计算机科学与技术\n";
            case "class" -> "major_name,grade,name,status\n计算机科学与技术,2024,1班,active\n";
            default -> throw new DomainException(
                    ErrorCode.VALIDATION_ERROR, "模板类型只能是 major 或 class");
        };
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }

    // ------------------------------------------------------------------ 解析

    private List<RawRow> parse(InputStream input, String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = input.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new DomainException(
                        ErrorCode.MATERIAL_TOO_LARGE, "导入文件超过 5 MB");
            }
            List<RawRow> rows = lower.endsWith(".xlsx") || lower.endsWith(".xls")
                    ? parseXlsx(bytes) : parseCsv(bytes);
            if (rows.size() > MAX_ROWS) {
                throw new DomainException(
                        ErrorCode.VALIDATION_ERROR, "单次导入不能超过 " + MAX_ROWS + " 行");
            }
            if (rows.isEmpty()) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "导入文件没有数据行");
            }
            return rows;
        } catch (IOException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "导入文件无法读取");
        }
    }

    private List<RawRow> parseCsv(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();
        List<RawRow> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                Map<String, String> values = new java.util.HashMap<>();
                record.toMap().forEach((key, value) ->
                        values.put(key.trim().toLowerCase(Locale.ROOT), value));
                rows.add(new RawRow((int) record.getRecordNumber() + 1, values));
            }
        }
        return rows;
    }

    private List<RawRow> parseXlsx(byte[] bytes) throws IOException {
        List<RawRow> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(
                new java.io.ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) {
                return rows;
            }
            List<String> columns = new ArrayList<>();
            for (Cell cell : header) {
                columns.add(cell.getStringCellValue().trim().toLowerCase(Locale.ROOT));
            }
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new java.util.HashMap<>();
                for (int c = 0; c < columns.size(); c++) {
                    Cell cell = row.getCell(c);
                    values.put(columns.get(c), cellText(cell));
                }
                if (values.values().stream().allMatch(v -> v == null || v.isBlank())) {
                    continue;
                }
                rows.add(new RawRow(i + 1, values));
            }
        }
        return rows;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double value = cell.getNumericCellValue();
                yield value == Math.rint(value)
                        ? String.valueOf((long) value) : String.valueOf(value);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }

    private static void failIfAny(List<ImportError> errors, int total) {
        if (!errors.isEmpty()) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "导入校验失败，共 " + errors.size() + " 行有问题，整批未写入",
                    Map.of("total", total, "errors", errors));
        }
    }

    /** 原始行：行号 + 列名小写后的值。 */
    private record RawRow(int lineNumber, Map<String, String> values) {
        String value(String column) {
            String v = values.get(column);
            return v == null || v.isBlank() ? null : v;
        }
    }

    public record ImportError(int row, String column, String message) {
    }

    public record ImportResult(int total, int created, int updated, List<ImportError> errors) {
    }
}
