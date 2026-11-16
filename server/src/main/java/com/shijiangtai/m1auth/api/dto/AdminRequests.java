package com.shijiangtai.m1auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** M1 管理端请求体（02 §4.1）。 */
public final class AdminRequests {

    private AdminRequests() {
    }

    /** 建校（FR-M1-03）：名称为平台唯一。 */
    public record CreateSchoolRequest(
            @NotBlank(message = "学校名称必填")
            @Size(max = 100, message = "学校名称不能超过 100 个字符") String name) {
    }

    public record UpdateSchoolRequest(
            @NotBlank(message = "学校名称必填")
            @Size(max = 100, message = "学校名称不能超过 100 个字符") String name) {
    }

    public record CreateMajorRequest(
            @NotBlank(message = "专业名称必填")
            @Size(max = 100, message = "专业名称不能超过 100 个字符") String name) {
    }

    /** 局部更新：字段为空表示不改；{@code active=false} 为停用。 */
    public record UpdateMajorRequest(String name, Boolean active) {
    }

    public record CreateClassRequest(
            @NotNull(message = "专业必填") UUID majorId,
            @NotBlank(message = "年级必填")
            @Size(max = 20, message = "年级不能超过 20 个字符") String grade,
            @NotBlank(message = "班名必填")
            @Size(max = 50, message = "班名不能超过 50 个字符") String name) {
    }

    public record UpdateClassRequest(UUID majorId, String grade, String name, Boolean active) {
    }

    /** 修改教师专业范围：至少一个本校启用专业（FR-M1-08）。 */
    public record UpdateTeacherMajorsRequest(
            @NotEmpty(message = "至少保留一个有效专业") List<UUID> majorIds) {
    }

    /**
     * 授予 / 撤销管理员角色（FR-M1-12）。
     *
     * <p>{@code admin=true} 叠加管理员角色（教师兼管理员），{@code false} 撤销；
     * 主身份不变，只能操作本校教师。
     */
    public record UpdateTeacherRolesRequest(
            @NotNull(message = "admin 必填") Boolean admin) {
    }
}
