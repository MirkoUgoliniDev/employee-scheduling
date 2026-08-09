package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "home_ui_settings")
public class HomeUiSettingsEntity extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "cover_key", nullable = false)
    public String coverKey = "";

    @Column(name = "cover_data_url", nullable = false)
    public String coverDataUrl = "";

    @Column(name = "title_key", nullable = false)
    public String titleKey = "home.title";

    @Column(name = "body_key", nullable = false)
    public String bodyKey = "home.body";

    @Column(name = "hint_key", nullable = false)
    public String hintKey = "home.hint";
}
