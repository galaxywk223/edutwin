package com.edutwin.teaching;

import com.edutwin.api.model.CourseSummary;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.List;

public interface CourseRepository {

    List<CourseSummary> findAccessible(EduTwinPrincipal principal);
}
