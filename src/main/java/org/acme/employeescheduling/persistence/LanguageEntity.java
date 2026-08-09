package org.acme.employeescheduling.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.acme.employeescheduling.dto.Language;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="languages")
public class LanguageEntity extends PanacheEntityBase {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Integer id;
 @Column(name="code",nullable=false,unique=true) public String code;
 @Column(name="description",nullable=false) public String description;
 @JdbcTypeCode(SqlTypes.INTEGER) @Column(name="active",nullable=false) public boolean active;
 public Language toDto(){return new Language(id,code,description,active);}
}
