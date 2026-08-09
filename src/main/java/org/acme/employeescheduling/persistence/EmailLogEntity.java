package org.acme.employeescheduling.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
@Entity @Table(name="email_log",uniqueConstraints=@UniqueConstraint(columnNames={"structure_id","employee_id","period_slug"}))
public class EmailLogEntity extends PanacheEntityBase {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Integer id;
 @Column(name="structure_id",nullable=false) public int structureId;
 @Column(name="employee_id",nullable=false) public int employeeId;
 @Column(name="period_slug",nullable=false) public String periodSlug;
 @Column(name="period_label",nullable=false) public String periodLabel="";
 @Column(name="sent_to",nullable=false) public String sentTo="";
 @Column(name="filename",nullable=false) public String filename="";
 @Column(name="sent_at",nullable=false) public String sentAt;
}
