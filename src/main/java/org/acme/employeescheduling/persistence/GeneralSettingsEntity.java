package org.acme.employeescheduling.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="general_settings")
public class GeneralSettingsEntity extends PanacheEntityBase {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Integer id;
 @Column(name="structure_id",nullable=false,unique=true) public int structureId;
 @Column(name="shift_window_mode",nullable=false) public String shiftWindowMode="month";
 @JdbcTypeCode(SqlTypes.INTEGER) @Column(name="auto_populate_from_template",nullable=false) public boolean autoPopulateFromTemplate;
}
