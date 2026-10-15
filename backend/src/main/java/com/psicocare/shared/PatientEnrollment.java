package com.psicocare.shared;

import java.util.UUID;

public interface PatientEnrollment {
    UUID enroll(Actor actor, String name, String email, UUID professional);
}
