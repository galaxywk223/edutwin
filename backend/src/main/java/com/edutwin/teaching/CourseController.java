package com.edutwin.teaching;

import com.edutwin.api.CoursesApi;
import com.edutwin.api.model.CourseList;
import com.edutwin.identity.EduTwinPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CourseController implements CoursesApi {

    private final CourseRepository courseRepository;

    public CourseController(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @Override
    public ResponseEntity<CourseList> listAccessibleCourses() {
        EduTwinPrincipal principal = (EduTwinPrincipal) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
        var courses = courseRepository.findAccessible(principal);
        return ResponseEntity.ok(new CourseList(courses, courses.size()));
    }
}
