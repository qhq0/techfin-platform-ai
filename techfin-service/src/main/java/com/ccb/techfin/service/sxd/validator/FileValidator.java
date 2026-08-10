package com.ccb.techfin.service.sxd.validator;

import com.ccb.techfin.common.exception.FileValidationException;
import com.ccb.techfin.service.sxd.config.FileUploadConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上传文件校验器：校验文件大小、扩展名/MIME 白名单、文件名合法性，
 * 以及文件头魔数与扩展名匹配（防伪装文件）。
 * <p>
 * 校验失败时抛出 {@link FileValidationException} 并记录审计日志
 * （时间、校验码、文件名、失败原因）。
 * </p>
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileValidator {

    private final FileUploadConfig uploadConfig;

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "image/jpeg",
            "image/png"
    );

    /** 文件名最大长度 */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /** 文件名非法字符（路径分隔符及常见危险字符；控制字符另查） */
    private static final Pattern INVALID_FILE_NAME_CHARS = Pattern.compile("[/\\\\:*?\"<>|]");

    /**
     * 扩展名 → 文件头魔数前缀（十六进制，读文件前 8 字节做前缀匹配）。
     * csv 为纯文本无固定魔数，不在此表（跳过魔数校验）。
     */
    private static final Map<String, List<String>> MAGIC_BYTES = Map.ofEntries(
            Map.entry("pdf", List.of("25504446")),                    // %PDF
            Map.entry("doc", List.of("d0cf11e0")),                    // OLE2 复合文档
            Map.entry("xls", List.of("d0cf11e0")),
            Map.entry("ppt", List.of("d0cf11e0")),
            Map.entry("docx", List.of("504b0304", "504b0506", "504b0708")), // ZIP（OOXML）
            Map.entry("xlsx", List.of("504b0304", "504b0506", "504b0708")),
            Map.entry("pptx", List.of("504b0304", "504b0506", "504b0708")),
            Map.entry("jpg", List.of("ffd8ff")),                      // JPEG
            Map.entry("jpeg", List.of("ffd8ff")),
            Map.entry("png", List.of("89504e470d0a1a0a"))             // PNG
    );

    /**
     * 校验文件（合并所有业务类型的扩展名，适用于单一上传接口）。
     */
    public void validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        long maxFileSize = uploadConfig.getMaxFileSize();
        Set<String> allowedExts = new HashSet<>();
        for (List<String> exts : uploadConfig.getAllowedExtensions().values()) {
            allowedExts.addAll(exts);
        }
        doValidate(files, maxFileSize, allowedExts);
    }

    private void doValidate(List<MultipartFile> files, long maxFileSize, Set<String> allowedExts) {

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw fail(null, "FILE_EMPTY", "上传文件不能为空");
            }

            String originalName = file.getOriginalFilename();
            long fileSize = file.getSize();

            if (fileSize > maxFileSize) {
                throw fail(originalName, "FILE_TOO_LARGE",
                        "超过大小限制（最大 " + maxFileSize / (1024 * 1024) + "MB），当前大小 "
                                + fileSize / (1024 * 1024) + "MB");
            }

            validateFileName(originalName);

            String extension = getExtension(originalName);
            if (extension == null || !allowedExts.contains(extension)) {
                throw fail(originalName, "INVALID_FILE_FORMAT",
                        "文件格式不支持，仅支持 " + String.join(", ", allowedExts));
            }

            String contentType = file.getContentType();
            if (contentType != null && !ALLOWED_MIME_TYPES.contains(contentType)) {
                if (!"csv".equals(extension)) {
                    throw fail(originalName, "INVALID_FILE_FORMAT",
                            "文件类型不合法");
                }
            }

            validateMagicBytes(file, extension, originalName);
        }
    }

    /**
     * 文件名合法性校验：非空、长度受限、无控制字符、无路径分隔符等危险字符。
     */
    private void validateFileName(String fileName) {
        if (fileName == null) {
            throw fail(null, "INVALID_FILE_NAME", "文件名不能为空");
        }
        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            throw fail(fileName, "INVALID_FILE_NAME",
                    "文件名过长（最大 " + MAX_FILE_NAME_LENGTH + " 字符）");
        }
        if (containsControlChar(fileName)) {
            throw fail(fileName, "INVALID_FILE_NAME",
                    "文件名不能包含换行等控制字符");
        }
        if (INVALID_FILE_NAME_CHARS.matcher(fileName).find()) {
            throw fail(fileName, "INVALID_FILE_NAME",
                    "文件名包含 / \\ : * ? \" < > | 等非法字符");
        }
    }

    /**
     * 文件头魔数与扩展名匹配校验：读文件前 8 字节，与期望魔数前缀比对，
     * 防止伪装扩展名的可执行/脚本文件绕过白名单上传。
     */
    private void validateMagicBytes(MultipartFile file, String extension, String originalName) {
        List<String> expectedPrefixes = MAGIC_BYTES.get(extension);
        if (expectedPrefixes == null || expectedPrefixes.isEmpty()) {
            return; // 无魔数定义（如 csv 纯文本），跳过
        }
        String headHex;
        try (InputStream in = file.getInputStream()) {
            headHex = toHex(in.readNBytes(8));
        } catch (IOException e) {
            throw fail(originalName, "INVALID_FILE_CONTENT",
                    "文件内容读取失败：" + e.getMessage());
        }
        boolean matched = expectedPrefixes.stream().anyMatch(headHex::startsWith);
        if (!matched) {
            throw fail(originalName, "INVALID_FILE_CONTENT",
                    "文件内容与扩展名不匹配，疑似伪装文件");
        }
    }

    /**
     * 检查文件名是否含 CR/LF/NUL 控制字符。
     */
    private boolean containsControlChar(String fileName) {
        if (fileName == null) {
            return false;
        }
        return fileName.indexOf('\r') >= 0
                || fileName.indexOf('\n') >= 0
                || fileName.indexOf((char) 0) >= 0;
    }

    private String getExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot == -1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 记录校验失败审计日志（时间由日志系统自动打点），并返回对应异常。
     */
    private FileValidationException fail(String fileName, String code, String message) {
        log.warn("File validation failed: code={}, fileName={}, reason={}", code, fileName, message);
        return new FileValidationException(fileName, code, message);
    }
}
