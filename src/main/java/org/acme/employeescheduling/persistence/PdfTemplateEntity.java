package org.acme.employeescheduling.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
@Entity @Table(name="pdf_templates")
public class PdfTemplateEntity extends PanacheEntityBase {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Integer id;
 @Column(name="structure_id",nullable=false,unique=true) public int structureId;
 @Column(name="header_text",nullable=false) public String headerText="";
 @Column(name="footer_text",nullable=false) public String footerText="";
 @Column(name="logo_data_url",nullable=false) public String logoDataUrl="";
 @Column(name="primary_color",nullable=false) public String primaryColor="#2980B9";
}
