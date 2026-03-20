// CVP Infrastructure — full deployment
// Deploys both backend services to the existing Azure Container Apps environment.
//
// Usage:
//   az deployment group create \
//     --resource-group YOUR_RESOURCE_GROUP \
//     --template-file infra/main.bicep \
//     --parameters \
//         azureVisionKey=<key> \
//         mcpBearerToken=<secret> \
//         gatewayBearerToken=<secret>
//
// Or use deploy.sh which wraps this.

@description('Azure region')
param location string = resourceGroup().location

@description('Container Apps Environment resource ID')
param containerAppsEnvironmentId string = '/subscriptions/YOUR_SUBSCRIPTION_ID/resourceGroups/YOUR_RESOURCE_GROUP/providers/Microsoft.App/managedEnvironments/YOUR_CONTAINER_APPS_ENV'

@description('Container Registry login server')
param containerRegistry string = 'YOUR_REGISTRY.azurecr.io'

@description('Image tag to deploy')
param imageTag string = 'latest'

// ── Gateway params ────────────────────────────────────────────────────────────

@description('Existing gateway Container App name')
param gatewayAppName string = 'cvpac'

@description('Azure Computer Vision endpoint')
param azureVisionEndpoint string = 'https://YOUR_VISION_RESOURCE.cognitiveservices.azure.com/'

@description('Azure Computer Vision key')
@secure()
param azureVisionKey string

@description('Optional bearer token to require on /v1/vision/analyze calls')
@secure()
param gatewayBearerToken string = ''

// ── MCP Server params ─────────────────────────────────────────────────────────

@description('MCP server Container App name')
param mcpAppName string = 'cvp-mcp-server'

@description('Bearer token for MCP server auth')
@secure()
param mcpBearerToken string

// ── Gateway Container App update ──────────────────────────────────────────────

resource gatewayApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: gatewayAppName
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    environmentId: containerAppsEnvironmentId
    configuration: {
      ingress: {
        external: true
        targetPort: 8000
        transport: 'http'
      }
      registries: [
        {
          server: containerRegistry
          identity: 'system'
        }
      ]
      secrets: [
        {
          name: 'azure-vision-key'
          value: azureVisionKey
        }
        {
          name: 'gateway-bearer-token'
          value: gatewayBearerToken
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'gateway'
          image: '${containerRegistry}/cvp-gateway:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            {
              name: 'AZURE_VISION_ENDPOINT'
              value: azureVisionEndpoint
            }
            {
              name: 'AZURE_VISION_KEY'
              secretRef: 'azure-vision-key'
            }
            {
              name: 'GATEWAY_BEARER_TOKEN'
              secretRef: 'gateway-bearer-token'
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 3
      }
    }
  }
}

// ── MCP Server Container App ──────────────────────────────────────────────────

resource mcpServerApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: mcpAppName
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    environmentId: containerAppsEnvironmentId
    configuration: {
      ingress: {
        external: true
        targetPort: 8001
        transport: 'http'
        corsPolicy: {
          allowedOrigins: ['*']
          allowedMethods: ['GET', 'POST', 'OPTIONS']
          allowedHeaders: ['Authorization', 'Content-Type']
        }
      }
      registries: [
        {
          server: containerRegistry
          identity: 'system'
        }
      ]
      secrets: [
        {
          name: 'mcp-bearer-token'
          value: mcpBearerToken
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'mcp-server'
          image: '${containerRegistry}/cvp-mcp-server:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            {
              name: 'MCP_BEARER_TOKEN'
              secretRef: 'mcp-bearer-token'
            }
            {
              name: 'DB_PATH'
              value: '/data/cvp_demo.db'
            }
          ]
          volumeMounts: [
            {
              volumeName: 'sqlite-data'
              mountPath: '/data'
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 3
      }
      volumes: [
        {
          name: 'sqlite-data'
          storageType: 'EmptyDir'
        }
      ]
    }
  }
}

// ── Outputs ───────────────────────────────────────────────────────────────────

output gatewayUrl string = 'https://${gatewayApp.properties.configuration.ingress.fqdn}'
output mcpServerUrl string = 'https://${mcpServerApp.properties.configuration.ingress.fqdn}/mcp'
output gatewayIdentityPrincipalId string = gatewayApp.identity.principalId
output mcpIdentityPrincipalId string = mcpServerApp.identity.principalId
