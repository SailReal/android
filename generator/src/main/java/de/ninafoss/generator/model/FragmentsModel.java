package de.ninafoss.generator.model;

import java.util.Set;
import java.util.TreeSet;

public class FragmentsModel {

	private final Set<FragmentModel> fragments;

	private FragmentsModel(Set<FragmentModel> fragments) {
		this.fragments = fragments;
	}

	public static FragmentsModel.Builder builder() {
		return new Builder();
	}

	public Set<FragmentModel> getFragments() {
		return fragments;
	}

	public String getJavaPackage() {
		return "de.ninafoss.presentation.ui.fragment";
	}

	public String getClassName() {
		return "Fragments";
	}

	public static class Builder {

		private final Set<FragmentModel> fragments = new TreeSet<>();

		private Builder() {
		}

		public Builder add(FragmentModel fragment) {
			fragments.add(fragment);
			return this;
		}

		public FragmentsModel build() {
			return new FragmentsModel(fragments);
		}

	}

}
