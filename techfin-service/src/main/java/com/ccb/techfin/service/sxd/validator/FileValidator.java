package com.ccb.techfin.service.sxd.validator;

import com.ccb.techfin.common.exception.FileValidationException;
import com.ccb.techfin.service.sxd.config.FileUploadConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上传文件校验器：校验文件大小和扩展名/MIME 类型。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
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
            String originalName = file.getOriginalFilename();
            long fileSize = file.getSize();

            if (fileSize > maxFileSize) {
                throw new FileValidationException(originalName, "FILE_TOO_LARGE",
                        "超过大小限制（最大 " + maxFileSize / (1024 * 1024) + "MB），当前大小 "
                                + fileSize / (1024 * 1024) + "MB");
            }

            String extension = getExtension(originalName);
            if (extension == null || !allowedExts.contains(extension)) {
                throw new FileValidationException(originalName, "INVALID_FILE_FORMAT",
                        "文件格式不支持，仅支持 " + String.join(", ", allowedExts));
            }

            String contentType = file.getContentType();
            if (contentType != null && !ALLOWED_MIME_TYPES.contains(contentType)) {
                if (!"csv".equals(extension)) {
                    throw new FileValidationException(originalName, "INVALID_FILE_FORMAT",
                            "文件类型不合法");
                }
            }
        }
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
}
