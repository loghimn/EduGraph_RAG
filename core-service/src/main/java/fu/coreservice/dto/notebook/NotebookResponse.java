package fu.coreservice.dto.notebook;
import fu.coreservice.entity.Course;
public record NotebookResponse(Long id, String title, String description, String domain, String language) {
    public static NotebookResponse from(Course c) {
        return new NotebookResponse(c.getCourseId(), c.getCourseName(), c.getDescription(), c.getDomain(), c.getLanguage());
    }
}
