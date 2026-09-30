package com.ccb.techfin.model.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import lombok.Data;

import java.util.List;

/**
 * 统一的外部接口响应包装类。
 * 所有调用外部 API 的返回都使用此类，data 字段由调用方按需转换。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class
ExternalResponse {

    private boolean success;
    private String code;
    private String message;
    private Object data;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 将 data 字段转换为指定类型。
     * 如果 data 已经是目标类型则直接强转，否则通过 Jackson 转换。
     */
    @SuppressWarnings("unchecked")
    public <T> T getDataAs(Class<T> type) {
        if (data == null) {
            return null;
        }
        if (type.isInstance(data)) {
            return type.cast(data);
        }
        return MAPPER.convertValue(data, type);
    }

    /**
     * 将 data 字段（JSON 数组）转换为指定元素类型的 List。
     */
    public <T> List<T> getDataAsList(Class<T> elementType) {
        if (data == null) {
            return null;
        }
        CollectionType listType = MAPPER.getTypeFactory()
                .constructCollectionType(List.class, elementType);
        return MAPPER.convertValue(data, listType);
    }
}
