package com.shijiangtai.m3classroom.application.view;

import java.util.UUID;

public record StudentVirtualStudentView(
        UUID virtualStudentId, String name, String avatar, int seatNo) {
}
