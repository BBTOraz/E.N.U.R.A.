package bbt.tao.orchestra.tools.formatter.impl;

import bbt.tao.orchestra.dto.enu.portal.EnuStaffMember;
import bbt.tao.orchestra.dto.enu.portal.EnuStaffSearchResponse;
import bbt.tao.orchestra.tools.formatter.ToolResponseFormatter;
import org.springframework.stereotype.Component;

@Component
public class StaffInfoResponseFormatter implements ToolResponseFormatter<EnuStaffSearchResponse> {

    @Override
    public String format(EnuStaffSearchResponse response) {
        if (response == null) {
            return "Не найдено ни одного преподавателя по заданным параметрам.";
        }

        var sb = new StringBuilder("Найденные сотрудники:\n\n");
        for (EnuStaffMember member : response.members()) {
            sb.append("ФИО: ").append(member.fullnameRu()).append("\n")
                    .append("должность: ").append(member.staffPostName()).append("\n")
                    .append("академическая должность: ").append(member.tutorPostName()).append("\n")
                    .append("научное подразделение: ").append(member.tutorUnitName()).append("\n")
                    .append("подразделение: ").append(member.staffUnitName()).append("\n")
                    .append("Рабочий телефон: ").append(member.phoneWork()).append("\n\n")
                    .append("Мобильный телефон: ").append(member.phoneMobile()).append("\n")
                    .append("Электронная почта: ").append(member.email()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
