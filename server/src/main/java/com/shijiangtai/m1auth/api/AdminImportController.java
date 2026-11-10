package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.application.M1PrincipalProvider;
import com.shijiangtai.m1auth.application.OrgImportService;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 组织导入与模板下载（FR-M1-04、03 §3.6）。
 * 整批事务：任一行校验失败则不写入任何数据，错误在响应 {@code details.errors}。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminImportController {

    private final OrgImportService imports;
    private final M1PrincipalProvider principals;

    public AdminImportController(OrgImportService imports, M1PrincipalProvider principals) {
        this.imports = imports;
        this.principals = principals;
    }

    @PostMapping(value = "/majors/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OrgImportService.ImportResult importMajors(@RequestParam("file") MultipartFile file)
            throws IOException {
        return imports.importMajors(
                principals.require(), file.getInputStream(), file.getOriginalFilename());
    }

    @PostMapping(value = "/classes/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OrgImportService.ImportResult importClasses(@RequestParam("file") MultipartFile file)
            throws IOException {
        return imports.importClasses(
                principals.require(), file.getInputStream(), file.getOriginalFilename());
    }

    @GetMapping("/imports/templates/{type}")
    public ResponseEntity<byte[]> template(@PathVariable String type) {
        byte[] body = imports.template(type);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + type + "-template.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }
}
