package com.morales.chemicallab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChemicalLabBackendApplication {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("--file-validation-worker")) {
			com.morales.chemicallab.security.files.FileValidationWorker.main(
					java.util.Arrays.copyOfRange(args, 1, args.length));
			return;
		}
		SpringApplication.run(ChemicalLabBackendApplication.class, args);
	}

}
