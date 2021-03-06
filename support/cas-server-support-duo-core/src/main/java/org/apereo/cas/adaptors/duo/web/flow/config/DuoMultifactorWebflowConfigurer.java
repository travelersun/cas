package org.apereo.cas.adaptors.duo.web.flow.config;

import org.apereo.cas.adaptors.duo.authn.DuoCredential;
import org.apereo.cas.configuration.model.support.mfa.MultifactorAuthenticationProperties;
import org.apereo.cas.services.MultifactorAuthenticationProvider;
import org.apereo.cas.services.VariegatedMultifactorAuthenticationProvider;
import org.apereo.cas.web.flow.AbstractMultifactorTrustedDeviceWebflowConfigurer;
import org.apereo.cas.web.flow.CasWebflowConstants;
import org.apereo.cas.web.flow.DynamicFlowModelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.webflow.config.FlowDefinitionRegistryBuilder;
import org.springframework.webflow.definition.registry.FlowDefinitionRegistry;
import org.springframework.webflow.engine.builder.FlowBuilder;
import org.springframework.webflow.engine.builder.model.FlowModelFlowBuilder;
import org.springframework.webflow.engine.builder.support.FlowBuilderServices;
import org.springframework.webflow.engine.model.AbstractActionModel;
import org.springframework.webflow.engine.model.AbstractStateModel;
import org.springframework.webflow.engine.model.ActionStateModel;
import org.springframework.webflow.engine.model.BinderModel;
import org.springframework.webflow.engine.model.BindingModel;
import org.springframework.webflow.engine.model.EndStateModel;
import org.springframework.webflow.engine.model.EvaluateModel;
import org.springframework.webflow.engine.model.TransitionModel;
import org.springframework.webflow.engine.model.VarModel;
import org.springframework.webflow.engine.model.ViewStateModel;
import org.springframework.webflow.engine.model.builder.DefaultFlowModelHolder;
import org.springframework.webflow.engine.model.registry.FlowModelHolder;

import java.util.LinkedList;

/**
 * This is {@link DuoMultifactorWebflowConfigurer}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public class DuoMultifactorWebflowConfigurer extends AbstractMultifactorTrustedDeviceWebflowConfigurer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DuoMultifactorWebflowConfigurer.class);
    
    private final VariegatedMultifactorAuthenticationProvider provider;

    public DuoMultifactorWebflowConfigurer(final FlowBuilderServices flowBuilderServices, final FlowDefinitionRegistry loginFlowDefinitionRegistry,
                                           final boolean enableDeviceRegistration, final VariegatedMultifactorAuthenticationProvider provider) {
        super(flowBuilderServices, loginFlowDefinitionRegistry, enableDeviceRegistration);
        this.provider = provider;
    }

    @Override
    protected void doInitialize() throws Exception {
        provider.getProviders().forEach(p -> {
            final FlowDefinitionRegistry duoFlowRegistry = buildDuoFlowRegistry(p);
            applicationContext.getAutowireCapableBeanFactory().initializeBean(duoFlowRegistry, p.getId());
            final ConfigurableListableBeanFactory cfg = (ConfigurableListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
            cfg.registerSingleton(p.getId(), duoFlowRegistry);
            registerMultifactorProviderAuthenticationWebflow(getLoginFlow(), p.getId(), duoFlowRegistry);
        });

        casProperties.getAuthn().getMfa().getDuo().stream()
                .filter(MultifactorAuthenticationProperties.Duo::isTrustedDeviceEnabled)
                .forEach(duo -> {
                    final String id = duo.getId();
                    try {
                        LOGGER.debug("Activating multifactor trusted authentication for webflow [{}]", id);
                        final FlowDefinitionRegistry registry = applicationContext.getBean(id, FlowDefinitionRegistry.class);
                        registerMultifactorTrustedAuthentication(registry);
                    } catch (final Exception e) {
                        LOGGER.error("Failed to register multifactor trusted authentication for " + id, e);
                    }
                });
    }

    private FlowDefinitionRegistry buildDuoFlowRegistry(final MultifactorAuthenticationProvider p) {
        final DynamicFlowModelBuilder modelBuilder = new DynamicFlowModelBuilder();

        // vars
        final LinkedList<VarModel> vars = new LinkedList<>();
        vars.add(new VarModel(CasWebflowConstants.VAR_ID_CREDENTIAL, DuoCredential.class.getName()));
        modelBuilder.setVars(vars);

        // starts
        final LinkedList<AbstractActionModel> starts = new LinkedList<>();
        starts.add(new EvaluateModel("initialFlowSetupAction"));
        modelBuilder.setOnStartActions(starts);

        // states
        final LinkedList<AbstractStateModel> states = new LinkedList<>();

        ActionStateModel actModel = new ActionStateModel(CasWebflowConstants.STATE_ID_INIT_LOGIN_FORM);
        LinkedList<AbstractActionModel> actions = new LinkedList<>();
        actions.add(new EvaluateModel("initializeLoginAction"));
        actModel.setActions(actions);

        LinkedList<TransitionModel> trans = new LinkedList<>();
        TransitionModel transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_SUCCESS);
        transModel.setTo("determineDuoRequest");
        trans.add(transModel);

        actModel.setTransitions(trans);
        states.add(actModel);

        ///////////////

        actModel = new ActionStateModel("determineDuoRequest");
        actions = new LinkedList<>();
        actions.add(new EvaluateModel("checkWebAuthenticationRequestAction"));
        actModel.setActions(actions);

        trans = new LinkedList<>();

        transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_YES);
        transModel.setTo("viewLoginFormDuo");
        trans.add(transModel);

        transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_NO);
        transModel.setTo("doNonWebAuthentication");
        trans.add(transModel);

        actModel.setTransitions(trans);
        states.add(actModel);

        ///////////////

        actModel = new ActionStateModel("doNonWebAuthentication");
        actions = new LinkedList<>();
        actions.add(new EvaluateModel("duoNonWebAuthenticationAction"));
        actModel.setActions(actions);

        trans = new LinkedList<>();

        transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_SUCCESS);
        transModel.setTo("finalizeAuthentication");
        trans.add(transModel);

        actModel.setTransitions(trans);
        states.add(actModel);

        ///////////////

        actModel = new ActionStateModel("finalizeAuthentication");
        actions = new LinkedList<>();
        actions.add(new EvaluateModel("duoAuthenticationWebflowAction"));
        actModel.setActions(actions);

        trans = new LinkedList<>();

        transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_SUCCESS);
        transModel.setTo(CasWebflowConstants.TRANSITION_ID_SUCCESS);
        trans.add(transModel);

        actModel.setTransitions(trans);
        states.add(actModel);

        /////////////////

        final ViewStateModel viewState = new ViewStateModel("viewLoginFormDuo");
        viewState.setView("casDuoLoginView");
        viewState.setModel(CasWebflowConstants.VAR_ID_CREDENTIAL);
        final BinderModel bm = new BinderModel();
        final LinkedList<BindingModel> bindings = new LinkedList<>();
        final BindingModel bme = new BindingModel("signedDuoResponse", null, null);
        bindings.add(bme);
        bm.setBindings(bindings);
        viewState.setBinder(bm);

        actions = new LinkedList<>();
        actions.add(new EvaluateModel("prepareDuoWebLoginFormAction"));
        viewState.setOnEntryActions(actions);

        transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_SUBMIT);
        transModel.setTo(CasWebflowConstants.TRANSITION_ID_REAL_SUBMIT);
        transModel.setBind(Boolean.TRUE.toString());
        transModel.setValidate(Boolean.FALSE.toString());

        trans.add(transModel);
        viewState.setTransitions(trans);
        states.add(viewState);

        /////////////////

        actModel = new ActionStateModel(CasWebflowConstants.TRANSITION_ID_REAL_SUBMIT);
        actions = new LinkedList<>();
        actions.add(new EvaluateModel("duoAuthenticationWebflowAction"));
        actModel.setActions(actions);

        trans = new LinkedList<>();

        transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_SUCCESS);
        transModel.setTo(CasWebflowConstants.TRANSITION_ID_SUCCESS);
        trans.add(transModel);

        transModel = new TransitionModel();
        transModel.setOn(CasWebflowConstants.TRANSITION_ID_ERROR);
        transModel.setTo(CasWebflowConstants.STATE_ID_INIT_LOGIN_FORM);
        trans.add(transModel);

        actModel.setTransitions(trans);
        states.add(actModel);

        ////////////////////

        states.add(new EndStateModel(CasWebflowConstants.TRANSITION_ID_SUCCESS));

        ////////////////////

        modelBuilder.setStates(states);


        final FlowModelHolder holder = new DefaultFlowModelHolder(modelBuilder);
        final FlowBuilder flowBuilder = new FlowModelFlowBuilder(holder);
        final FlowDefinitionRegistryBuilder builder = new FlowDefinitionRegistryBuilder(this.applicationContext, flowBuilderServices);
        builder.addFlowBuilder(flowBuilder, p.getId());
        return builder.build();
    }
}
