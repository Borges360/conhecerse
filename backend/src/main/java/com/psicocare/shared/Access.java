package com.psicocare.shared;

import static com.psicocare.shared.Store.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Access {
    private final Store db;

    public Access(Store db) {
        this.db = db;
    }

    public UUID professional(Actor a) {
        return id(
                db.one(
                        "select id from app.professionals where tenant_id=? and user_id=? and"
                                + " verification_status='VERIFIED'",
                        a.tenant(),
                        a.user()),
                "id");
    }

    public boolean clinical(Actor a, UUID patient) {
        return a.has("PSYCHOLOGIST")
                && db.exists(
                        "select 1 from app.care_assignments ca join app.professionals p on"
                                + " p.tenant_id=ca.tenant_id and p.id=ca.professional_id where"
                                + " ca.tenant_id=? and ca.patient_id=? and p.user_id=? and"
                                + " p.verification_status='VERIFIED' and ca.start_at<=now() and"
                                + " (ca.end_at is null or ca.end_at>now())",
                        a.tenant(),
                        patient,
                        a.user());
    }

    public void requireClinical(Actor a, UUID patient) {
        if (!clinical(a, patient)) throw Problem.notFound();
    }

    public boolean portal(Actor a, UUID patient) {
        return db.exists(
                "select 1 from app.patients where tenant_id=? and id=? and portal_user_id=?",
                a.tenant(),
                patient,
                a.user());
    }

    public boolean guardian(Actor a, UUID patient, String scope) {
        return db.exists(
                "select 1 from app.guardian_patient_access where tenant_id=? and patient_id=? and"
                        + " guardian_user_id=? and legal_verified_at is not null and not"
                        + " review_required and valid_from<=now() and (valid_until is null or"
                        + " valid_until>now()) and ?=any(access_scope)",
                a.tenant(),
                patient,
                a.user(),
                scope);
    }

    public void patient(Actor a, UUID patient, boolean write) {
        boolean staff = a.has("OWNER", "ADMIN", "SECRETARY") || clinical(a, patient);
        boolean own = portal(a, patient) || guardian(a, patient, "SCHEDULE");
        if (!(staff || (!write && own))) throw Problem.notFound();
    }

    public void appointment(Actor a, Map<String, Object> appointment, boolean staffOnly) {
        UUID patient = id(appointment, "patient_id");
        boolean staff = a.has("OWNER", "ADMIN", "SECRETARY", "BILLING") || clinical(a, patient);
        if (!(staff || (!staffOnly && (portal(a, patient) || guardian(a, patient, "SCHEDULE")))))
            throw Problem.notFound();
    }

    public void billing(Actor a, Map<String, Object> invoice, boolean write) {
        if (a.has("OWNER", "ADMIN", "BILLING")) return;
        if (!write
                && db.exists(
                        "select 1 from app.invoice_payers ip join app.patients p on"
                                + " p.tenant_id=ip.tenant_id and p.id=ip.patient_id where"
                                + " ip.tenant_id=? and ip.id=? and (p.portal_user_id=? or exists"
                                + " (select 1 from app.guardian_patient_access g where"
                                + " g.tenant_id=p.tenant_id and g.patient_id=p.id and"
                                + " g.guardian_user_id=? and g.legal_verified_at is not null and"
                                + " not g.review_required and g.valid_from<=now() and"
                                + " (g.valid_until is null or g.valid_until>now()) and"
                                + " 'BILLING'=any(g.access_scope)))",
                        a.tenant(),
                        invoice.get("payer_id"),
                        a.user(),
                        a.user())) return;
        throw Problem.notFound();
    }
}
