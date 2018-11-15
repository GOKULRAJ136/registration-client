package io.mosip.kernel.masterdata.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.mosip.kernel.masterdata.dto.BiometricAttributeDto;
import io.mosip.kernel.masterdata.dto.BiometricTypeResponseDto;
import io.mosip.kernel.masterdata.service.BiometricAttributeService;
import io.swagger.annotations.ApiOperation;

/**
 * 
 * biometric attribute controller with api to get list of biometric attributes
 * best on biometric code type and language code.
 * 
 * @author Uday Kumar
 * @since 1.0.0
 *
 */
@RestController
public class BiometricAttributeController {

	@Autowired
	BiometricAttributeService biometricAttributeService;

	@ApiOperation(value = "Fetch all the biometric attributes avialbale for specific BiometricType")
	@GetMapping("/getbiometricattributesbyauthtype/{languagecode}/{biometrictypecode}")
	public BiometricTypeResponseDto getBiometricAttributesByBiometricType(
			@PathVariable("languagecode") String languageCode,
			@PathVariable("biometrictypecode") String biometricTypeCode) {
		List<BiometricAttributeDto> biomentricAttributes = biometricAttributeService
				.getBiometricAttribute(biometricTypeCode, languageCode);
		return new BiometricTypeResponseDto(biomentricAttributes);
	}
}
