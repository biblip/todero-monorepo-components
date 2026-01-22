package com.example.todero.component.template;

import com.social100.todero.processor.EventDefinition;

public enum TemplateEvents implements EventDefinition {
  TEMPLATE_EVENT;

  @Override
  public String getDescription() {
    return "Emitted by the template component event command.";
  }
}
