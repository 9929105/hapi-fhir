package org.hl7.fhir.dstu3.hapi.validation;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.hamcrest.core.StringContains;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.Condition;
import org.hl7.fhir.dstu3.model.Condition.ConditionClinicalStatus;
import org.hl7.fhir.dstu3.model.Condition.ConditionVerificationStatus;
import org.hl7.fhir.dstu3.model.DateType;
import org.hl7.fhir.dstu3.model.EligibilityResponse;
import org.hl7.fhir.dstu3.model.EligibilityResponse.BenefitComponent;
import org.hl7.fhir.dstu3.model.Narrative.NarrativeStatus;
import org.hl7.fhir.dstu3.model.OperationOutcome;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.StringType;
import org.hl7.fhir.dstu3.model.UnsignedIntType;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.parser.XmlParserDstu3Test;
import ca.uhn.fhir.util.TestUtil;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SchemaBaseValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.validation.schematron.SchematronBaseValidator;

public class ResourceValidatorDstu3Test {

	private static FhirContext ourCtx = FhirContext.forDstu3();
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ResourceValidatorDstu3Test.class);

	@AfterClass
	public static void afterClassClearContext() {
		TestUtil.clearAllStaticFieldsForUnitTest();
	}

	/**
	 * See issue #50
	 */
	@Test()
	public void testOutOfBoundsDate() {
		Patient p = new Patient();
		p.setBirthDateElement(new DateType("2000-12-31"));

		// Put in an invalid date
		IParser parser = ourCtx.newXmlParser();
		parser.setParserErrorHandler(new StrictErrorHandler());

		String encoded = parser.setPrettyPrint(true).encodeResourceToString(p).replace("2000-12-31", "2000-15-31");
		ourLog.info(encoded);

		assertThat(encoded, StringContains.containsString("2000-15-31"));

		ValidationResult result = ourCtx.newValidator().validateWithResult(encoded);
		String resultString = parser.setPrettyPrint(true).encodeResourceToString(result.toOperationOutcome());
		ourLog.info(resultString);

		assertEquals(2, ((OperationOutcome) result.toOperationOutcome()).getIssue().size());
		assertThat(resultString, StringContains.containsString("cvc-pattern-valid"));

		try {
			parser.parseResource(encoded);
			fail();
		} catch (DataFormatException e) {
			assertEquals("DataFormatException at [[row,col {unknown-source}]: [2,4]]: Invalid attribute value \"2000-15-31\": Invalid date/time format: \"2000-15-31\"", e.getMessage());
		}
	}

	/**
	 * Make sure that the elements that appear in all resources (meta, language, extension, etc) all appear in the correct order
	 */
	@Test
	public void testValidateResourceWithResourceElements() {

		XmlParserDstu3Test.TestPatientFor327 patient = new XmlParserDstu3Test.TestPatientFor327();
		patient.setBirthDate(new Date());
		patient.setId("123");
		patient.getText().setDivAsString("<div>FOO</div>");
		patient.getText().setStatus(NarrativeStatus.GENERATED);
		patient.getLanguageElement().setValue("en");
		patient.addExtension().setUrl("http://foo").setValue(new StringType("MOD"));
		patient.getMeta().setLastUpdated(new Date());

		List<Reference> conditions = new ArrayList<Reference>();
		Condition condition = new Condition();
		condition.getSubject().setReference("Patient/123");
		condition.addBodySite().setText("BODY SITE");
		condition.getCode().setText("CODE");
		condition.setClinicalStatus(ConditionClinicalStatus.ACTIVE);
		condition.setVerificationStatus(ConditionVerificationStatus.CONFIRMED);
		conditions.add(new Reference(condition));
		patient.setCondition(conditions);
		patient.addIdentifier().setSystem("http://foo").setValue("123");

		String encoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(patient);
		ourLog.info(encoded);

		FhirValidator val = ourCtx.newValidator();
		val.registerValidatorModule(new SchemaBaseValidator(ourCtx));
		val.registerValidatorModule(new SchematronBaseValidator(ourCtx));
		val.registerValidatorModule(new FhirInstanceValidator());

		ValidationResult result = val.validateWithResult(encoded);

		OperationOutcome operationOutcome = (OperationOutcome) result.toOperationOutcome();
		String ooencoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(operationOutcome);
		ourLog.info(ooencoded);

		assertTrue(result.isSuccessful());

		assertThat(ooencoded, containsString("Unknown extension http://foo"));
	}

	/**
	 * See https://groups.google.com/d/msgid/hapi-fhir/a266083f-6454-4cf0-a431-c6500f052bea%40googlegroups.com?utm_medium= email&utm_source=footer
	 */
	@Test
	public void testValidateWithExtensionsXml() {
		PatientProfileDstu3 myPatient = new PatientProfileDstu3();
		myPatient.setId("1");
		myPatient.setColorPrimary(new CodeableConcept().addCoding(new Coding().setSystem("http://example.com#animalColor").setCode("furry-grey")));
		myPatient.setColorSecondary(new CodeableConcept().addCoding(new Coding().setSystem("http://example.com#animalColor").setSystem("furry-white")));
		myPatient.setOwningOrganization(new Reference("Organization/2.25.79433498044103547197447759549862032393"));
		myPatient.addName().setFamily("FamilyName");
		myPatient.addExtension().setUrl("http://foo.com/example").setValue(new StringType("String Extension"));

		IParser p = ourCtx.newXmlParser().setPrettyPrint(true);
		String messageString = p.encodeResourceToString(myPatient);
		ourLog.info(messageString);

		//@formatter:off
		assertThat(messageString, stringContainsInOrder(
			"meta",
			"Organization/2.25.79433498044103547197447759549862032393",
			"furry-grey",
			"furry-white",
			"String Extension",
			"FamilyName"
		));
		assertThat(messageString, not(stringContainsInOrder(
			"extension",
			"meta"
		)));
		assertThat(messageString, containsString("url=\"http://ahr.copa.inso.tuwien.ac.at/StructureDefinition/Patient#animal-colorSecondary\""));
		assertThat(messageString, containsString("url=\"http://foo.com/example\""));
		//@formatter:on

		FhirValidator val = ourCtx.newValidator();
		val.registerValidatorModule(new SchemaBaseValidator(ourCtx));
		val.registerValidatorModule(new SchematronBaseValidator(ourCtx));
		val.registerValidatorModule(new FhirInstanceValidator());

		ValidationResult result = val.validateWithResult(messageString);

		OperationOutcome operationOutcome = (OperationOutcome) result.toOperationOutcome();
		String encoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(operationOutcome);
		ourLog.info(encoded);

		assertTrue(result.isSuccessful());

		assertThat(messageString, containsString("valueReference"));
		assertThat(messageString, not(containsString("valueResource")));
	}

	/**
	 * Per email from Jon Zammit
	 */
	@Test
	@Ignore
	public void testValidateQuestionnaire() throws IOException {
		String input = IOUtils.toString(getClass().getResourceAsStream("/questionnaire_jon_z_20160506.xml"), StandardCharsets.UTF_8);

		FhirValidator val = ourCtx.newValidator();
		val.registerValidatorModule(new FhirInstanceValidator());

		ValidationResult result = val.validateWithResult(input);

		OperationOutcome operationOutcome = (OperationOutcome) result.toOperationOutcome();
		String ooencoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(operationOutcome);
		ourLog.info(ooencoded);

		assertTrue(result.isSuccessful());
	}

	@Test
	public void testValidateJsonNumericId() {
		String input = "{\"resourceType\": \"Patient\",\n" +
				"  \"id\": 123,\n" +
				"  \"meta\": {\n" +
				"    \"versionId\": \"29\",\n" +
				"    \"lastUpdated\": \"2015-12-22T19:53:11.000Z\"\n" +
				"  },\n" +
				"  \"communication\": {\n" +
				"    \"language\": {\n" +
				"      \"coding\": [\n" +
				"        {\n" +
				"          \"system\": \"urn:ietf:bcp:47\",\n" +
				"          \"code\": \"hi\",\n" +
				"          \"display\": \"Hindi\",\n" +
				"          \"userSelected\": false\n" +
				"        }],\n" +
				"      \"text\": \"Hindi\"\n" +
				"    },\n" +
				"    \"preferred\": true\n" +
				"  }\n" +
				"}";

		FhirValidator val = ourCtx.newValidator();
		val.registerValidatorModule(new FhirInstanceValidator());
		ValidationResult output = val.validateWithResult(input);

		OperationOutcome operationOutcome = (OperationOutcome) output.toOperationOutcome();
		String encoded = ourCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(operationOutcome);
		ourLog.info(encoded);

		assertThat(encoded, containsString("Error parsing JSON: the primitive value must be a string"));

	}

	/**
	 * See https://groups.google.com/d/msgid/hapi-fhir/a266083f-6454-4cf0-a431-c6500f052bea%40googlegroups.com?utm_medium= email&utm_source=footer
	 */
	@Test
	public void testValidateWithExtensionsJson() {
		PatientProfileDstu3 myPatient = new PatientProfileDstu3();
		myPatient.setId("1");
		myPatient.setColorPrimary(new CodeableConcept().addCoding(new Coding().setSystem("http://example.com#animalColor").setCode("furry-grey")));
		myPatient.setColorSecondary(new CodeableConcept().addCoding(new Coding().setSystem("http://example.com#animalColor").setSystem("furry-white")));
		myPatient.setOwningOrganization(new Reference("Organization/2.25.79433498044103547197447759549862032393"));
		myPatient.addName().setFamily("FamilyName");
		myPatient.addExtension().setUrl("http://foo.com/example").setValue(new StringType("String Extension"));

		IParser p = ourCtx.newJsonParser().setPrettyPrint(true);
		String messageString = p.encodeResourceToString(myPatient);
		ourLog.info(messageString);

		//@formatter:off
		assertThat(messageString, stringContainsInOrder(
			"meta",
			"String Extension",
			"Organization/2.25.79433498044103547197447759549862032393",
			"furry-grey",
			"furry-white",
			"FamilyName"
		));
		assertThat(messageString, not(stringContainsInOrder(
			"extension",
			"meta"
		)));
		//@formatter:on

		FhirValidator val = ourCtx.newValidator();
		val.registerValidatorModule(new SchemaBaseValidator(ourCtx));
		val.registerValidatorModule(new SchematronBaseValidator(ourCtx));
		val.registerValidatorModule(new FhirInstanceValidator());

		ValidationResult result = val.validateWithResult(messageString);

		OperationOutcome operationOutcome = (OperationOutcome) result.toOperationOutcome();
		String encoded = ourCtx.newXmlParser().setPrettyPrint(true).encodeResourceToString(operationOutcome);
		ourLog.info(encoded);

		assertTrue(result.isSuccessful());

		assertThat(messageString, containsString("valueReference"));
		assertThat(messageString, not(containsString("valueResource")));
	}

	@Test
	public void testValidateDifferentPropertyButSameStartsWithPath() throws Exception {

		EligibilityResponse fhirObj = new EligibilityResponse();
		BenefitComponent benComp = fhirObj.addInsurance().addBenefitBalance().addFinancial();
		// Test between .benefit[x] and benefitUsed[x]
		benComp.setBenefitUsed(new UnsignedIntType(2));

		String input = ourCtx.newXmlParser().encodeResourceToString(fhirObj);

		FhirValidator validator = ourCtx.newValidator();
		validator.registerValidatorModule(new FhirInstanceValidator());

		ValidationResult result = validator.validateWithResult(input);
		// we should get some results, not an exception
		assertEquals(4, result.getMessages().size());
	}

}
