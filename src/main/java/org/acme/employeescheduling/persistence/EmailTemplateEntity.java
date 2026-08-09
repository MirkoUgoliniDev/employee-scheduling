package org.acme.employeescheduling.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
@Entity @Table(name="email_templates")
public class EmailTemplateEntity extends PanacheEntityBase {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Integer id;
 @Column(name="structure_id",nullable=false,unique=true) public int structureId;
 @Column(name="subject",nullable=false) public String subject="";
 @Column(name="body",nullable=false) public String body="";
}
